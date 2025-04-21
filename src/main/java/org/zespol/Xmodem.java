package org.zespol;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class Xmodem {

    // --- Stałe protokołu ---
    private static final byte SOH = 0x01;    // Start of Header (pakiet 128 bajtów danych)
    private static final byte EOT = 0x04;    // End of Transmission
    private static final byte ACK = 0x06;    // Acknowledge (potwierdzenie)
    private static final byte NAK = 0x15;    // Negative Acknowledge (brak potwierdzenia, żądanie retransmisji)
    private static final byte CAN = 0x18;    // Cancel (anulowanie transmisji)
    private static final byte SUB = 0x1A;    // Substitute (znak wypełniający, Ctrl+Z)
    private static final byte CHAR_C = 0x43; // 'C' - żądanie rozpoczęcia transmisji z CRC

    // --- Konfiguracja transferu ---
    private static final int BLOCK_SIZE = 128; // Standardowy rozmiar bloku danych
    private static final int MAX_RETRIES = 10;   // Maksymalna liczba prób retransmisji bloku (nadajnik) / odbioru bloku (odbiornik)
    private static final int MAX_INIT_RETRIES = 6; // Max prób wysłania NAK/C na początku przez odbiornik (6 * 10s = 1 minuta) lub max czas czekania nadajnika na NAK/C
    private static final long INIT_TIMEOUT_MS = 10000; // Czas oczekiwania odbiornika na pierwszy blok (SOH) po wysłaniu NAK/C (10 sekund) lub czas oczekiwania nadajnika na NAK/C
    private static final long ACK_TIMEOUT_MS = 5000;  // Czas oczekiwania na ACK/NAK po wysłaniu bloku (nadajnik) lub czas oczekiwania na kolejny SOH/EOT po wysłaniu ACK (odbiornik)
    private static final long EOT_ACK_TIMEOUT_MS = 5000; // Czas oczekiwania na ACK po wysłaniu EOT (nadajnik)

    // --- Stany transferu ---
    public enum TransferState {
        IDLE,           // Bezczynny
        // Stany odbiornika
        RECEIVER_INIT,  // Odbiornik: Stan początkowy, wysyłanie NAK/C
        EXPECTING_SOH,  // Odbiornik: Oczekuje na SOH (lub EOT)
        RECEIVING,      // Odbiornik: Przetwarza odebrany blok SOH (stan przejściowy)
        // Stany nadajnika
        SENDER_WAIT_INIT, // Nadajnik: Czeka na NAK lub 'C' od odbiornika
        SENDING,        // Nadajnik: Wysyła blok danych
        WAITING_FOR_ACK,// Nadajnik: Czeka na ACK/NAK po wysłaniu bloku
        SENDING_EOT,    // Nadajnik: Wysyła EOT
        WAITING_FOR_EOT_ACK, // Nadajnik: Czeka na ACK po EOT
        // Stany końcowe
        COMPLETED,      // Transfer zakończony sukcesem
        ABORTED,        // Transfer anulowany (przez CAN lub błędy)
        ERROR           // Wystąpił błąd krytyczny (np. IO)
    }

    // --- Pola klasy ---
    private volatile TransferState currentState = TransferState.IDLE; // Aktualny stan transferu (volatile dla bezpieczeństwa wątkowego)
    private final SerialCommunicator communicator; // Obiekt do komunikacji przez port szeregowy
    private final List<Byte> receiveBuffer = new ArrayList<>(); // Bufor na przychodzące dane (NOWOŚĆ: kluczowy dla poprawnego działania)
    private String outputFileName; // Nazwa pliku do zapisu (odbiornik)
    private FileOutputStream fileOutputStream; // Strumień do zapisu pliku (odbiornik)
    private boolean useCRC = false; // Czy używać CRC (true) czy sumy kontrolnej (false)

    private int expectedBlockNumber = 1; // Oczekiwany numer bloku (odbiornik)
    private int receiveRetries = 0; // Licznik prób inicjalizacji lub odbioru danego bloku (odbiornik)
    private int sendRetries = 0; // Licznik prób wysłania danego bloku lub EOT (nadajnik) / prób inicjalizacji (nadajnik)

    // Wątek do obsługi timeoutów
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeoutTaskHandler; // Uchwyt do bieżącego zadania timeoutu

    // Zmienne dla nadajnika
    private byte[] fileData; // Dane pliku do wysłania (ładowane do pamięci)
    private int currentBlockIndex = 0; // Indeks bieżącego bloku do wysłania (0-based)

    // --- Konstruktor ---
    public Xmodem(SerialCommunicator communicator) {
        this.communicator = communicator;
        if (this.communicator == null) {
            throw new IllegalArgumentException("SerialCommunicator nie może być null");
        }
        // Upewnij się, że SerialCommunicator wie o tym Xmodem (może już ustawione w Main)
        this.communicator.setXmodem(this);
    }

    // --- Gettery / Settery ---
    public void setOutputFileName(String fileName) {
        this.outputFileName = fileName;
    }

    public TransferState getCurrentState() {
        return currentState;
    }

    // --- Metody Odbiornika ---

    /**
     * Rozpoczyna proces odbierania pliku.
     * @param useCRC Jeśli true, żąda użycia CRC ('C'), w przeciwnym razie sumy kontrolnej (NAK).
     */
    public void startReceive(boolean useCRC) {
        if (currentState != TransferState.IDLE) {
            System.err.println("Nie można rozpocząć odbioru, transfer już w toku lub nie zakończony poprawnie. Stan: " + currentState);
            return;
        }
        if (outputFileName == null || outputFileName.isEmpty()) {
            System.err.println("Nie ustawiono nazwy pliku wyjściowego.");
            currentState = TransferState.ERROR;
            return;
        }

        try {
            // Utwórz ścieżki i plik
            File file = new File(outputFileName);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                     throw new IOException("Nie można utworzyć katalogu: " + parentDir.getAbsolutePath());
                }
            }
            fileOutputStream = new FileOutputStream(file);
            System.out.println("Plik wyjściowy otwarty: " + outputFileName);

            this.useCRC = useCRC;
            this.expectedBlockNumber = 1;
            this.receiveRetries = 0;
            this.currentState = TransferState.RECEIVER_INIT; // Ustaw stan na inicjalizację
            System.out.println("Rozpoczynanie odbioru. Tryb: " + (useCRC ? "CRC" : "Suma kontrolna"));
            initiateTransferSignal(); // Wyślij pierwszy NAK/C i ustaw timeout

        } catch (IOException e) {
            System.err.println("Błąd podczas otwierania pliku wyjściowego '" + outputFileName + "': " + e.getMessage());
            currentState = TransferState.ERROR;
            closeResources(); // Zamknij plik, jeśli się otworzył
        }
    }

    /**
     * Wysyła początkowy NAK lub 'C' i ustawia timeout oczekiwania na pierwszy blok (SOH).
     * Wywoływane w stanie RECEIVER_INIT.
     */
    private void initiateTransferSignal() {
        cancelTimeoutTask(); // Anuluj stary timeout, jeśli istnieje
        if (receiveRetries >= MAX_INIT_RETRIES) {
            System.err.println("Przekroczono maksymalną liczbę prób inicjalizacji odbioru. Anuluję.");
            abortTransfer(false);
            return;
        }

        byte signal = useCRC ? CHAR_C : NAK;
        System.out.println("--> Wysyłam sygnał inicjujący: " + (useCRC ? "'C'" : "NAK") + " (próba " + (receiveRetries + 1) + ")");
        communicator.sendData(new byte[]{signal});
        currentState = TransferState.EXPECTING_SOH; // Po wysłaniu sygnału czekamy na SOH

        // Ustaw timeout oczekiwania na SOH
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleReceiveTimeout, INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        System.out.println("Ustawiono timeout " + INIT_TIMEOUT_MS + "ms na oczekiwanie SOH/EOT.");
        receiveRetries++; // Zwiększ licznik prób inicjalizacji
    }

    /**
     * Metoda wywoływana przez timeoutScheduler, gdy upłynie czas oczekiwania na SOH/EOT (odbiornik).
     */
    private void handleReceiveTimeout() {
        // Upewnij się, że timeout jest nadal aktywny (nie został anulowany przez odebranie danych)
        if (timeoutTaskHandler == null || timeoutTaskHandler.isDone()) {
            return; // Timeout został anulowany w międzyczasie
        }

        System.out.println("\n[Timeout Odbiornika] Nie odebrano SOH/EOT w oczekiwanym czasie.");

        // Jeśli byliśmy w trakcie inicjalizacji (czekaliśmy na pierwszy SOH)
        if (currentState == TransferState.EXPECTING_SOH && expectedBlockNumber == 1) {
             System.out.println("Ponawiam wysłanie sygnału inicjującego NAK/C.");
             currentState = TransferState.RECEIVER_INIT; // Wróć do stanu wysyłania NAK/C
             initiateTransferSignal(); // Spróbuj ponownie wysłać NAK/C
        }
        // Jeśli czekaliśmy na kolejny blok (nie pierwszy) lub EOT
        else if (currentState == TransferState.EXPECTING_SOH) {
             System.out.println("Nie odebrano kolejnego bloku SOH ani EOT. Ponawiam wysłanie NAK.");
             // Xmodem standardowo nie ponawia ACK, ale wysyła NAK jeśli nie dostaje bloku
             // Zwiększamy licznik prób dla bieżącego bloku
             receiveRetries++;
             if (receiveRetries >= MAX_RETRIES) {
                 System.err.println("Przekroczono maksymalną liczbę prób odbioru bloku " + expectedBlockNumber + ". Anuluję.");
                 abortTransfer(false);
             } else {
                 sendNak(); // Wyślij NAK, żeby poprosić o retransmisję oczekiwanego bloku
                 resetReceiveTimeout(); // Ustaw nowy timeout
             }
        } else {
                 // Wysłanie NAK nie ma sensu, bo nie wiemy co poszło nie tak
                 // Można by spróbować anulować i zacząć od nowa, albo po prostu anulować
             System.err.println("Timeout odbiornika w nieoczekiwanym stanie: " + currentState);
             // Można rozważyć przerwanie transferu w takim przypadku
             // abortTransfer(false);
        }
        // Timeouty w innych stanach (np. IDLE, COMPLETED) są ignorowane
    }

    /**

     * Główna metoda odbierająca dane z SerialCommunicator.
     * NOWOŚĆ: Ta metoda *tylko* dodaje dane do `receiveBuffer` i wywołuje `processInternalBuffer`.
     * @param data Tablica bajtów odebrana z portu szeregowego.
     */
    public void ReceivedDataFromSerial(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        // Synchronizacja na buforze jest ważna, aby uniknąć ConcurrentModificationException,
        // gdy wątek listenera portu dodaje dane, a wątek przetwarzający (lub timeout) może je modyfikować.
        // Dodaj odebrane bajty do wewnętrznego bufora
        synchronized (receiveBuffer) {
            for (byte b : data) {
                receiveBuffer.add(b);
            }
            // Logowanie może być przydatne, ale generuje dużo danych
            // System.out.println("<-- Dodano " + data.length + " bajtów do bufora. Rozmiar: " + receiveBuffer.size());
        }
        // Po dodaniu danych, spróbuj przetworzyć zawartość bufora.
        // Przetwórz bufor
        processInternalBuffer();
    }

    /**
     * NOWOŚĆ: Przetwarza dane zgromadzone w `receiveBuffer`.
     * Działa w pętli, dopóki może zinterpretować i przetworzyć kompletne jednostki Xmodem
     * (pojedyncze bajty sterujące lub całe bloki SOH) z początku bufora.
     * Metoda jest `synchronized`, aby zapewnić, że tylko jeden wątek na raz przetwarza bufor.
     */
    private synchronized void processInternalBuffer() {
        // System.out.println("[Buffer Proc] Start. Stan: " + currentState + ", Rozmiar: " + receiveBuffer.size());

        boolean processedSomething; // Flaga, aby kontynuować pętlę, jeśli coś zostało przetworzone
        do {
            processedSomething = false;
            if (receiveBuffer.isEmpty()) {
                // System.out.println("[Buffer Proc] Bufor pusty, koniec przetwarzania.");
                break; // Nic do przetworzenia
            }

            // Sprawdzamy pierwszy bajt bez usuwania go jeszcze
            byte firstByte = receiveBuffer.getFirst();
            // System.out.println("[Buffer Proc] Pierwszy bajt: " + String.format("0x%02X", firstByte) + " w stanie " + currentState);

            // ----- LOGIKA ODBIORNIKA -----
            if (currentState == TransferState.EXPECTING_SOH || currentState == TransferState.RECEIVER_INIT /* Na wszelki wypadek */) {
                if (firstByte == SOH) {
                        // Mamy początek pakietu
                    int requiredLength = 1 /*SOH*/ + 1 /*Blk#*/ + 1 /*~Blk#*/ + BLOCK_SIZE + (useCRC ? 2 : 1) /*CRC/Chk*/;
                    if (receiveBuffer.size() >= requiredLength) {
                        // Mamy wystarczająco danych na cały blok SOH
                        System.out.println("[Buffer Proc] Wykryto SOH, wystarczająca długość (" + receiveBuffer.size() + " >= " + requiredLength + "). Przetwarzam blok.");
                        cancelTimeoutTask(); // Anuluj timeout oczekiwania na SOH/EOT
                        byte[] block = extractBytesFromBuffer(requiredLength); // Wyciągnij blok z bufora
                        processXmodemBlock(block); // Przetwórz blok (to ustawi stan i ewentualny nowy timeout)
                        processedSomething = true; // Przetworzyliśmy blok, kontynuuj pętlę
                    } else {
                        // Wykryto SOH, ale dane są niekompletne, czekamy na resztę
                         System.out.println("[Buffer Proc] Wykryto SOH, ale za mało danych (" + receiveBuffer.size() + " < " + requiredLength + "). Czekam na więcej.");
                        break; // Przerwij pętlę, poczekaj na więcej danych
                    }
                } else if (firstByte == EOT) {
                    // Odebrano EOT
                    System.out.println("[Buffer Proc] Wykryto EOT.");
                    cancelTimeoutTask(); // Anuluj timeout oczekiwania na SOH/EOT
                    extractBytesFromBuffer(1); // Usuń EOT z bufora
                    completeTransfer(); // Zakończ transfer (wyśle ACK, ustawi stan COMPLETED)
                    processedSomething = true; // Zakończyliśmy, ale coś przetworzyliśmy
                } else if (firstByte == CAN) {
                    // Odebrano CAN - przerwanie transferu przez nadawcę
                    System.out.println("[Buffer Proc] Wykryto CAN. Anulowanie transferu.");
                    cancelTimeoutTask();
                    extractBytesFromBuffer(1); // Usuń CAN
                    // Standardowo Xmodem wymaga dwóch CAN, ale często jeden wystarczy
                    // Można dodać logikę sprawdzania drugiego CAN, jeśli jest w buforze
                    abortTransfer(true); // true - zainicjowane zdalnie
                    processedSomething = true;
                }
                 else {
                    // Nieoczekiwany bajt w stanie oczekiwania na SOH/EOT/CAN
                    System.out.println("[Buffer Proc] Oczekiwano SOH/EOT/CAN, otrzymano nieznany bajt: " + String.format("0x%02X", firstByte) + ". Odrzucam.");
                    extractBytesFromBuffer(1); // Usuń nieoczekiwany bajt
                    processedSomething = true; // Odrzuciliśmy coś, spróbujmy dalej
                 }
            }
            // ----- LOGIKA NADAJNIKA -----
            else if (currentState == TransferState.SENDER_WAIT_INIT) {
                if (firstByte == NAK) {
                    System.out.println("[Buffer Proc] Odebrano NAK (żądanie startu, suma kontrolna).");
                    cancelTimeoutTask(); // Anuluj timeout oczekiwania na NAK/C
                    extractBytesFromBuffer(1);
                    useCRC = false; // Odbiornik wybrał sumę kontrolną
                    System.out.println("Nadajnik: Rozpoczynam wysyłanie bloku 1 (Suma kontrolna).");
                    sendRetries = 0; // Zresetuj licznik prób dla wysyłania
                    sendNextBlock(); // Wyślij pierwszy blok (to ustawi stan SENDING i timeout ACK)
                    processedSomething = true;
                } else if (firstByte == CHAR_C) {
                    System.out.println("[Buffer Proc] Odebrano 'C' (żądanie startu, CRC).");
                    cancelTimeoutTask();
                    extractBytesFromBuffer(1);
                    useCRC = true; // Odbiornik wybrał CRC
                    System.out.println("Nadajnik: Rozpoczynam wysyłanie bloku 1 (CRC).");
                    sendRetries = 0;
                    sendNextBlock();
                    processedSomething = true;
                } else if (firstByte == CAN) {
                     System.out.println("[Buffer Proc] Odebrano CAN podczas oczekiwania na NAK/C.");
                     cancelTimeoutTask();
                     extractBytesFromBuffer(1);
                     abortTransfer(true);
                     processedSomething = true;
                } else {
                    System.out.println("[Buffer Proc] Oczekiwano NAK/'C'/CAN, otrzymano nieznany bajt: " + String.format("0x%02X", firstByte) + ". Odrzucam.");
                    extractBytesFromBuffer(1); // Usuń śmiecia
                    processedSomething = true;
                }
            } else if (currentState == TransferState.WAITING_FOR_ACK) {
                if (firstByte == ACK) {
                    System.out.println("[Buffer Proc] Odebrano ACK dla bloku " + (currentBlockIndex + 1) + ".");
                    cancelTimeoutTask(); // Anuluj timeout oczekiwania na ACK
                    extractBytesFromBuffer(1);
                    sendRetries = 0; // Zresetuj licznik prób dla tego bloku
                    currentBlockIndex++; // Przejdź do następnego bloku (indeks 0-based)
                    // Sprawdź, czy wysłaliśmy już wszystkie dane
                    int dataOffset = currentBlockIndex * BLOCK_SIZE;
                    if (dataOffset >= fileData.length) {
                        // Wszystkie bloki wysłane, wyślij EOT
                        System.out.println("Nadajnik: Wszystkie dane wysłane. Wysyłam EOT.");
                        sendEOT(); // To ustawi stan SENDING_EOT i timeout EOT_ACK
                    } else {
                        // Wyślij kolejny blok
                        // System.out.println("Nadajnik: Wysyłam kolejny blok: " + (currentBlockIndex + 1));
                        sendNextBlock(); // To ustawi stan SENDING i timeout ACK
                    }
                    processedSomething = true;
                } else if (firstByte == NAK) {
                    System.out.println("[Buffer Proc] Odebrano NAK dla bloku " + (currentBlockIndex + 1) + ". Ponawiam wysłanie.");
                    cancelTimeoutTask();
                    extractBytesFromBuffer(1);
                    handleSendRetry(); // Ponów wysłanie bieżącego bloku (to ustawi stan SENDING i timeout ACK)
                    processedSomething = true;
                } else if (firstByte == CAN) {
                     System.out.println("[Buffer Proc] Odebrano CAN podczas oczekiwania na ACK/NAK.");
                     cancelTimeoutTask();
                     extractBytesFromBuffer(1);
                     abortTransfer(true);
                     processedSomething = true;
                } else {
                     System.out.println("[Buffer Proc] Oczekiwano ACK/NAK/CAN, otrzymano nieznany bajt: " + String.format("0x%02X", firstByte) + ". Ignoruję i czekam na timeout lub poprawną odpowiedź.");
                     // Nie usuwamy bajtu - może to być np. SOH od odbiornika w wyniku błędu
                     // Polegamy na timeoutcie ACK, który spowoduje retransmisję.
                     break; // Przerwij pętlę i czekaj
                }
            } else if (currentState == TransferState.WAITING_FOR_EOT_ACK) {
                if (firstByte == ACK) {
                    System.out.println("[Buffer Proc] Odebrano ACK dla EOT. Transfer zakończony pomyślnie.");
                    cancelTimeoutTask(); // Anuluj timeout EOT ACK
                    extractBytesFromBuffer(1);
                    currentState = TransferState.COMPLETED; // Transfer zakończony sukcesem!
                    closeResources(); // Zamknij plik wejściowy
                    processedSomething = true;
                } else if (firstByte == CAN) {
                     System.out.println("[Buffer Proc] Odebrano CAN podczas oczekiwania na EOT ACK.");
                     cancelTimeoutTask();
                     extractBytesFromBuffer(1);
                     abortTransfer(true);
                     processedSomething = true;
                }
                         // Nie ma potrzeby więcej nic robić, wątek główny wykryje zmianę stanu
                else {
                    System.out.println("[Buffer Proc] Oczekiwano ACK dla EOT lub CAN, otrzymano: " + String.format("0x%02X", firstByte) + ". Ignoruję.");
                    // Ignorujemy inne bajty i czekamy na timeout EOT lub poprawny ACK.
                     break; // Przerwij pętlę i czekaj
                }
            }
            // --- Pozostałe stany ---
            else if (currentState == TransferState.IDLE || currentState == TransferState.COMPLETED || currentState == TransferState.ABORTED || currentState == TransferState.ERROR || currentState == TransferState.SENDING || currentState == TransferState.SENDING_EOT || currentState == TransferState.RECEIVING) {
                 // W tych stanach normalnie nie powinniśmy przetwarzać bufora w ten sposób
                 // (SENDING/SENDING_EOT/RECEIVING to stany przejściowe ustawiane tuż przed akcją)
                 // Jeśli coś jest w buforze, to prawdopodobnie śmieci lub dane przyszły za późno.
                 if (!receiveBuffer.isEmpty()) {
                     System.out.println("[Buffer Proc] Dane w buforze w nieoczekiwanym stanie (" + currentState + "). Czyszczę bajt: " + String.format("0x%02X", firstByte));
                     extractBytesFromBuffer(1); // Usuń jeden bajt na raz
                     processedSomething = true; // Coś usunęliśmy
                 }
            }

            // Jeśli nic nie przetworzono w tej iteracji (np. czekamy na więcej danych dla SOH),
            // a bufor nie jest pusty, wyjdź z pętli, aby uniknąć potencjalnej nieskończonej pętli.
            if (!processedSomething && !receiveBuffer.isEmpty()) {
                // System.out.println("[Buffer Proc] Nie przetworzono niczego w tej iteracji, ale bufor nie jest pusty (" + receiveBuffer.size() + " bajtów). Czekam na więcej danych lub timeout.");
                break;
            }

        } while (processedSomething && !receiveBuffer.isEmpty()); // Kontynuuj, jeśli coś przetworzono i są jeszcze dane w buforze

        // System.out.println("[Buffer Proc] Koniec. Stan: " + currentState + ", Rozmiar: " + receiveBuffer.size());
    }

    /**
     * NOWOŚĆ: Pomocnicza metoda do wyciągania i usuwania określonej liczby bajtów z początku `receiveBuffer`.
     * MUSI być wywoływana wewnątrz bloku `synchronized(receiveBuffer)` lub z metody `synchronized`.
     * @param count Liczba bajtów do wyciągnięcia.
     * @return Tablica `byte[]` zawierająca wyciągnięte bajty lub pusta tablica w przypadku błędu.
     */
    private byte[] extractBytesFromBuffer(int count) {
        if (count <= 0 || receiveBuffer.size() < count) {
            System.err.println("[Buffer Extract ERROR] Próba wyciągnięcia " + count + " bajtów z bufora o rozmiarze " + receiveBuffer.size());
            // Awaryjnie można wyczyścić bufor, ale to może być ryzykowne
            // receiveBuffer.clear();
            return new byte[0];
        }
        byte[] extracted = new byte[count];
        // Skuteczniejszy sposób kopiowania i usuwania z ArrayList
        for (int i = 0; i < count; i++) {
            extracted[i] = receiveBuffer.removeFirst(); // Usuwa element z początku i przesuwa resztę
        }
        // System.out.println("[Buffer Extract] Wyciągnięto " + count + " bajtów. Pozostało: " + receiveBuffer.size());
        return extracted;
    }


    /**
     * Przetwarza pojedynczy, kompletny blok danych Xmodem (SOH).
     * Wywoływana przez `processInternalBuffer` gdy w buforze znajdzie się kompletny blok.
     * @param block Tablica bajtów zawierająca kompletny blok (SOH, Nr, ~Nr, Dane[128], Chk/CRC).
     */
    private void processXmodemBlock(byte[] block) {
        currentState = TransferState.RECEIVING; // Ustawiamy stan na czas przetwarzania bloku

        // block[0] == SOH (sprawdzone wcześniej)
        byte blockNumberByte = block[1];
        byte blockNumberComplement = block[2];
        int blockNumber = blockNumberByte & 0xFF; // Konwersja na int bez znaku

        System.out.print("\n[Odbiornik] Przetwarzanie bloku SOH, Nr: " + blockNumber);

        // 1. Sprawdź poprawność numeru bloku i jego dopełnienia
        if (!verifyBlockNumber(blockNumberByte, blockNumberComplement)) {
            System.out.println(". Błąd: Niezgodny numer bloku (" + blockNumber + ") i dopełnienie (" + String.format("%02X", blockNumberComplement) + "). Oczekiwano: " + String.format("%02X", (byte) (255 - blockNumberByte)) + ".");
            handleBlockError(); // Wyśle NAK, ustawi stan EXPECTING_SOH i timeout
            return;
        }

        // 2. Sprawdź, czy to jest oczekiwany numer bloku lub duplikat poprzedniego
        int expectedNum = expectedBlockNumber % 256; // Upewnij się, że porównujemy w zakresie 0-255
        int previousNum = (expectedBlockNumber - 1 + 256) % 256; // Poprzedni numer bloku (modulo 256)

        if (blockNumber == expectedNum) {
            // Poprawny, oczekiwany blok
            System.out.print(". Oczekiwany numer (" + expectedNum + "). ");

            byte[] payload = Arrays.copyOfRange(block, 3, 3 + BLOCK_SIZE);
            boolean checksumOk;

            if (useCRC) {
                // Weryfikacja CRC-16
                int receivedCRC = ((block[3 + BLOCK_SIZE] & 0xFF) << 8) | (block[3 + BLOCK_SIZE + 1] & 0xFF);
                int calculatedCRC = calculateCRC16(payload);
                System.out.print("CRC Odb: " + String.format("%04X", receivedCRC) + ", Oblicz: " + String.format("%04X", calculatedCRC) + ". ");
                checksumOk = (receivedCRC == calculatedCRC);
            } else {
                // Weryfikacja sumy kontrolnej
                byte receivedChecksum = block[3 + BLOCK_SIZE];
                byte calculatedChecksum = calculateChecksum(payload);
                System.out.print("Suma Odb: " + String.format("%02X", receivedChecksum) + ", Oblicz: " + String.format("%02X", calculatedChecksum) + ". ");
                checksumOk = (receivedChecksum == calculatedChecksum);
            }

            if (checksumOk) {
                System.out.println("Suma kontrolna poprawna. Zapisuję dane.");
                if (savePayloadToFile(payload)) {
                    expectedBlockNumber++; // Przejdź do następnego numeru bloku TYLKO po poprawnym zapisie
                    receiveRetries = 0; // Zresetuj licznik błędów dla tego bloku
                    sendAck(); // Wyślij ACK
                    currentState = TransferState.EXPECTING_SOH; // Po ACK czekamy na kolejny SOH lub EOT
                    resetReceiveTimeout(); // Ustaw timeout na kolejny blok/EOT
                } else {
                    // Błąd zapisu pliku - traktujemy jak krytyczny błąd
                    System.err.println("Krytyczny błąd zapisu do pliku! Anuluję transfer.");
                    abortTransfer(false);
                }
            } else {
                System.out.println("Błąd sumy kontrolnej.");
                handleBlockError(); // Wyśle NAK, ustawi stan EXPECTING_SOH i timeout
            }

        } else if (blockNumber == previousNum) {
            // To jest duplikat poprzedniego bloku (nadawca nie dostał naszego ACK)
            System.out.println(". Duplikat bloku " + blockNumber + " (oczekiwano " + expectedNum + "). Wysyłam ponownie ACK.");
            sendAck(); // Wyślij ponownie ACK dla tego (poprzedniego) bloku
            // Nie inkrementuj expectedBlockNumber, nie zapisuj danych, nie resetuj receiveRetries
            currentState = TransferState.EXPECTING_SOH; // Nadal czekamy na właściwy blok expectedBlockNumber
            resetReceiveTimeout(); // Resetuj timeout po wysłaniu ACK

        } else {
            // Błąd sekwencji bloków - nie jest to ani oczekiwany, ani poprzedni blok. Poważny problem.
            System.err.println(". KRYTYCZNY BŁĄD SEKWENCJI! Oczekiwano bloku " + expectedNum + " lub " + previousNum + ", otrzymano " + blockNumber + ". Anuluję transfer.");
            abortTransfer(false); // Anuluj transfer
        }
    }

    /**

     * Resetuje timeout oczekiwania na SOH/EOT (odbiornik).
     * Używa stałej ACK_TIMEOUT_MS, ponieważ po wysłaniu ACK czekamy podobny czas jak nadajnik na ACK.
     */
    private void resetReceiveTimeout() {
        cancelTimeoutTask(); // Anuluj poprzedni timeout
        // System.out.println("Resetuję timeout odbiornika (" + ACK_TIMEOUT_MS + "ms) na SOH/EOT.");
        // Używamy krótszego timeoutu, gdy już trwa transmisja
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleReceiveTimeout, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**

     * Zapisuje dane (payload) do otwartego pliku.
     * Usuwa znaki wypełnienia SUB (0x1A) z końca danych.
     * @param payload Dane do zapisania (128 bajtów).
     * @return true jeśli zapis się powiódł, false w przypadku błędu IO.
     */
    private boolean savePayloadToFile(byte[] payload) {
        if (fileOutputStream == null) {
            System.err.println("Błąd krytyczny: Próba zapisu do pliku, ale strumień jest null.");
            return false;
        }
        try {
            // Znajdź pierwszy znak SUB od końca lub koniec danych
                // Opcjonalnie: usuń znaki SUB (0x1A) z końca bloku, jeśli istnieją
            int endOfData = payload.length;
            while (endOfData > 0 && payload[endOfData - 1] == SUB) {
                endOfData--;
            }

            if (endOfData > 0) {
                 // System.out.println("Zapisuję " + endOfData + " bajtów danych do pliku.");
                fileOutputStream.write(payload, 0, endOfData);
                fileOutputStream.flush(); // Opcjonalnie, dla pewności zapisu
            } else {
                 System.out.println("Blok zawierał same znaki SUB, nic nie zapisano.");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Błąd podczas zapisu danych do pliku: " + e.getMessage());
            return false;
        }
    }

    /**
     * Wysyła ACK (potwierdzenie).
     */
    private void sendAck() {
        System.out.println("--> Wysyłam ACK");
        communicator.sendData(new byte[]{ACK});
        // Timeout jest resetowany przez metodę wywołującą (np. processXmodemBlock),
        // która wie, na co teraz czekamy.
    }

    /**
     * Wysyła NAK (żądanie retransmisji).
     */
    // Wysyła NAK
    private void sendNak() {
        System.out.println("--> Wysyłam NAK");
        communicator.sendData(new byte[]{NAK});
        // Timeout jest resetowany przez metodę wywołującą (np. handleBlockError).
    }

    /**
     * Obsługa błędu odbioru bloku (np. zła suma kontrolna, zły numer/dopełnienie).
     * Wywoływana przez `processXmodemBlock`.
     * Wysyła NAK i resetuje timeout. Jeśli przekroczono próby, anuluje transfer.
     */
    private void handleBlockError() {
        receiveRetries++;
        System.out.println("Błąd odbioru bloku " + expectedBlockNumber + ". Próba: " + receiveRetries + "/" + MAX_RETRIES);
        if (receiveRetries >= MAX_RETRIES) {
            System.err.println("Przekroczono maksymalną liczbę prób odbioru bloku. Anuluję transfer.");
            abortTransfer(false);
        } else {
            sendNak(); // Wyślij NAK
            currentState = TransferState.EXPECTING_SOH; // Po NAK nadal oczekujemy na SOH (retransmisję)
            resetReceiveTimeout(); // Ustaw timeout na retransmisję
        }
    }

    /**
     * Kończy transfer po odebraniu EOT.
     * Wysyła ostatnie ACK i ustawia stan na COMPLETED.
     * Wywoływana przez `processInternalBuffer`.
     */
    private void completeTransfer() {
        // cancelTimeoutTask(); // Już anulowany w processInternalBuffer przed wywołaniem
        System.out.println("\n[Odbiornik] Odebrano EOT. Kończenie transferu.");
        sendAck(); // Wyślij ostatnie ACK jako potwierdzenie EOT
        currentState = TransferState.COMPLETED;
        closeResources(); // Zamknij plik wyjściowy
        System.out.println("Transfer zakończony pomyślnie. Plik zapisany jako: " + outputFileName);
        // Wątek główny wykryje zmianę stanu
    }

    /**
     * Anuluje transfer. Wysyła CAN (jeśli inicjowane lokalnie),
     * ustawia stan ABORTED, czyści bufor i zamyka zasoby.
     * @param remoteInitiated True, jeśli anulowanie zostało zainicjowane przez odebranie CAN.
     */
    void abortTransfer(boolean remoteInitiated) {
        // Sprawdź, czy już nie jesteśmy w stanie końcowym, aby uniknąć podwójnego anulowania
        if (currentState == TransferState.ABORTED || currentState == TransferState.COMPLETED || currentState == TransferState.ERROR) {
            System.out.println("Próba anulowania transferu, który jest już w stanie końcowym: " + currentState);
            return;
        }

        System.out.println("!!! Anulowanie transferu !!! (Inicjowane " + (remoteInitiated ? "zdalnie" : "lokalnie") + ")");
        cancelTimeoutTask(); // Zawsze anuluj aktywny timeout

        if (!remoteInitiated) {
            // Jeśli my inicjujemy przerwanie, wyślij dwa znaki CAN dla pewności
            System.out.println("--> Wysyłam CAN x2.");
            byte[] cancelSignal = {CAN, CAN};
            communicator.sendData(cancelSignal);
            // Krótka pauza, aby dać czas na wysłanie CAN przed potencjalnym zamknięciem portu
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        currentState = TransferState.ABORTED;

        closeResources(); // Zamknij plik (jeśli otwarty) i wyczyść dane nadajnika
        synchronized (receiveBuffer) { // Wyczyść bufor na wszelki wypadek
            System.out.println("Czyszczenie bufora odbiorczego (" + receiveBuffer.size() + " bajtów).");
            receiveBuffer.clear();
        }
        System.out.println("Transfer przerwany.");
    }

    /**
     * Anuluje bieżący timeout task, jeśli istnieje i nie został jeszcze wykonany.
     */
    private void cancelTimeoutTask() {
        if (timeoutTaskHandler != null) {
            if (!timeoutTaskHandler.isDone()) {
                // System.out.println("Anulowanie aktywnego timeoutu.");
                timeoutTaskHandler.cancel(false); // false - nie przerywaj, jeśli już działa
            }
            timeoutTaskHandler = null; // Usuń uchwyt
            // System.out.println("Anulowano timeout task."); // Opcjonalny log
        }
    }


    // --- Metody Nadajnika ---

    /**
     * Rozpoczyna proces wysyłania pliku.
     * @param filePath Ścieżka do pliku do wysłania.
     * @param useCRC Jeśli true, preferuje użycie CRC (czeka na 'C'), inaczej preferuje sumę kontrolną (czeka na NAK).
     */
    public void startSend(String filePath, boolean useCRC) {
        if (currentState != TransferState.IDLE) {
            System.err.println("Nie można rozpocząć wysyłania, transfer już w toku lub nie zakończony poprawnie. Stan: " + currentState);
            return;
        }

        // Wczytaj plik do pamięci
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                throw new FileNotFoundException("Plik nie istnieje lub nie jest plikiem: " + filePath);
            }
            if (file.length() == 0) {
                System.out.println("Ostrzeżenie: Plik '" + filePath + "' jest pusty. Wysyłam tylko EOT.");
                 // Specyficzna obsługa pustego pliku - od razu wysyłamy EOT po inicjalizacji
                 fileData = new byte[0]; // Pusta tablica
            } else {
                // Wczytaj cały plik do pamięci - UWAGA: Może być problematyczne dla dużych plików!
                fileData = Files.readAllBytes(Paths.get(filePath));
                 System.out.println("Plik wczytany do pamięci: " + filePath + " (" + fileData.length + " bajtów)");
            }

            this.useCRC = useCRC; // Zapamiętaj preferowany tryb
            this.currentBlockIndex = 0; // Zaczynamy od bloku 0 (wysyłany jako nr 1)
            this.sendRetries = 0; // Resetuj licznik prób inicjalizacji
            this.currentState = TransferState.SENDER_WAIT_INIT; // Ustaw stan oczekiwania na NAK/C

            System.out.println("Rozpoczynanie wysyłania. Oczekuję na sygnał startu (NAK lub 'C') od odbiornika...");
            resetSendInitiationTimeout(); // Ustaw timeout oczekiwania na NAK/C

        } catch (IOException e) {
            System.err.println("Błąd podczas odczytu pliku '" + filePath + "': " + e.getMessage());
            currentState = TransferState.ERROR;
            fileData = null; // Wyczyść dane pliku
        }
    }

    /**
     * Resetuje timeout oczekiwania na NAK/C na początku wysyłania.
     */
    private void resetSendInitiationTimeout() {
        cancelTimeoutTask(); // Anuluj stary timeout
        // System.out.println("Resetuję timeout nadajnika (" + INIT_TIMEOUT_MS + "ms) na oczekiwanie NAK/C.");
        // Używamy tego samego timeoutu co odbiornik na pierwszy blok
        // Dajemy odbiornikowi czas na wysłanie pierwszego NAK/C
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Metoda wywoływana przez timeoutScheduler, gdy upłynie czas oczekiwania na NAK/C (start) lub ACK/NAK (po bloku) lub ACK (po EOT).
     */
    private void handleSendTimeout() {
        // Upewnij się, że timeout jest nadal aktywny
        if (timeoutTaskHandler == null || timeoutTaskHandler.isDone()) {
            return;
        }

        System.out.println("\n[Timeout Nadajnika]");

        switch (currentState) {
            case SENDER_WAIT_INIT:
                System.out.println("Nie odebrano NAK ani 'C' w oczekiwanym czasie.");
                sendRetries++;
                if (sendRetries >= MAX_INIT_RETRIES) { // Użyj MAX_INIT_RETRIES do limitu czekania na start
                    System.err.println("Przekroczono maksymalny czas oczekiwania na rozpoczęcie transferu przez odbiornik. Anuluję.");
                    abortTransfer(false);
                } else {
                    System.out.println("Czekam dalej na NAK/C (próba " + (sendRetries + 1) + "/" + MAX_INIT_RETRIES + ")");
                    resetSendInitiationTimeout(); // Ustaw nowy timeout
                }
                break;

            case WAITING_FOR_ACK:
                System.out.println("Nie odebrano ACK ani NAK dla bloku " + (currentBlockIndex + 1) + " w oczekiwanym czasie.");
                handleSendRetry(); // Spróbuj wysłać blok ponownie
                break;

            case WAITING_FOR_EOT_ACK:
                System.out.println("Nie odebrano ACK dla EOT w oczekiwanym czasie.");
                handleEotRetry(); // Spróbuj wysłać EOT ponownie
                break;

            default:
                System.err.println("Timeout nadajnika w nieoczekiwanym stanie: " + currentState);
                // Rozważ przerwanie transferu
                // abortTransfer(false);
                break;
        }
         // Timeouty w innych stanach są ignorowane
    }

    /**
     * Resetuje timeout oczekiwania na ACK/NAK po wysłaniu bloku.
     */
    private void resetAckTimeout() {
        cancelTimeoutTask();
        // System.out.println("Resetuję timeout nadajnika (" + ACK_TIMEOUT_MS + "ms) na oczekiwanie ACK/NAK dla bloku " + (currentBlockIndex + 1));
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Resetuje timeout oczekiwania na ACK po wysłaniu EOT.
     */
    private void resetEotAckTimeout() {
        cancelTimeoutTask();
        // System.out.println("Resetuję timeout nadajnika (" + EOT_ACK_TIMEOUT_MS + "ms) na oczekiwanie ACK dla EOT.");
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, EOT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Konstruuje i wysyła następny blok danych.
     * Ustawia stan na WAITING_FOR_ACK i resetuje timeout ACK.
     * Wywoływana po otrzymaniu NAK/C (dla pierwszego bloku) lub ACK (dla kolejnych).
     */
    private void sendNextBlock() {
        if (fileData == null) {
             System.err.println("Błąd krytyczny: Próba wysłania bloku, ale dane pliku są null.");
             abortTransfer(false);
             return;
        }

        // Oblicz początek i koniec danych dla bieżącego bloku
        int dataOffset = currentBlockIndex * BLOCK_SIZE;
        if (dataOffset >= fileData.length) {
            // To nie powinno się zdarzyć, jeśli logika sprawdzania końca jest poprawna
            System.err.println("Błąd: Próba wysłania bloku poza zakresem danych pliku. Wysyłam EOT.");
            sendEOT();
            return;
        }

        int length = Math.min(BLOCK_SIZE, fileData.length - dataOffset);

        byte[] payload = new byte[BLOCK_SIZE]; // Zawsze 128 bajtów

        // Skopiuj dane pliku do payload
        System.arraycopy(fileData, dataOffset, payload, 0, length);

        // Wypełnij resztę payload znakiem SUB (0x1A), jeśli dane są krótsze niż BLOCK_SIZE
        // Jeśli blok nie jest pełny, wypełnij resztę znakiem SUB (0x1A)
        if (length < BLOCK_SIZE) {
            Arrays.fill(payload, length, BLOCK_SIZE, SUB);
            System.out.println("Wypełniono " + (BLOCK_SIZE - length) + " bajtów znakiem SUB.");
        }

        // Zbuduj pełny blok Xmodem
        byte blockNumber = (byte) ((currentBlockIndex + 1) % 256); // Numer bloku (1-255, zawijany)
        byte blockNumberComplement = (byte) (255 - blockNumber);
        byte[] block;

        // Oblicz i dodaj sumę kontrolną lub CRC
        if (useCRC) {
            int crc = calculateCRC16(payload);
            block = new byte[1 + 1 + 1 + BLOCK_SIZE + 2]; // SOH, Blk#, ~Blk#, Payload, CRC_H, CRC_L
            block[block.length - 2] = (byte) ((crc >> 8) & 0xFF); // CRC High byte
            block[block.length - 1] = (byte) (crc & 0xFF);       // CRC Low byte
        } else {
            byte checksum = calculateChecksum(payload);
            block = new byte[1 + 1 + 1 + BLOCK_SIZE + 1]; // SOH, Blk#, ~Blk#, Payload, Chk
            block[block.length - 1] = checksum;
        }

        block[0] = SOH;
        block[1] = blockNumber;
        block[2] = blockNumberComplement;
        System.arraycopy(payload, 0, block, 3, BLOCK_SIZE);

        System.out.println("--> Wysyłam blok SOH, Nr: " + (blockNumber & 0xFF) + ", Rozmiar: " + block.length + ", Tryb: " + (useCRC ? "CRC" : "Suma"));
        currentState = TransferState.SENDING; // Ustaw stan na czas wysyłania
        communicator.sendData(block);
        currentState = TransferState.WAITING_FOR_ACK; // Po wysłaniu czekamy na ACK/NAK
        resetAckTimeout(); // Ustaw timeout na odpowiedź
    }

    /**
     * Obsługuje ponowne wysłanie bieżącego bloku (po NAK lub timeout ACK).
     * Sprawdza limit prób retransmisji.
     * Wywoływana przez `processInternalBuffer` (po NAK) lub `handleSendTimeout`.
     */
    private void handleSendRetry() {
        sendRetries++;
        System.out.println("Ponowna próba wysłania bloku " + (currentBlockIndex + 1) + ". Próba: " + sendRetries + "/" + MAX_RETRIES);
        if (sendRetries >= MAX_RETRIES) {
            System.err.println("Przekroczono maksymalną liczbę prób wysłania bloku. Anuluję transfer.");
            abortTransfer(false);
        } else {
            // Wyślij ten sam blok ponownie
            sendNextBlock(); // Wyślij blok ponownie (to ustawi stan SENDING i timeout ACK)
        }
    }

    /**
     * Wysyła znak końca transmisji (EOT).
     * Ustawia stan na WAITING_FOR_EOT_ACK i resetuje timeout EOT ACK.
     * Wywoływana po otrzymaniu ACK dla ostatniego bloku.
     */
    private void sendEOT() {
        System.out.println("--> Wysyłam EOT");
        currentState = TransferState.SENDING_EOT;
        communicator.sendData(new byte[]{EOT});
        sendRetries = 0; // Zresetuj licznik prób dla EOT
        currentState = TransferState.WAITING_FOR_EOT_ACK;
        resetEotAckTimeout(); // Ustaw timeout oczekiwania na ACK dla EOT
    }

    /**
     * Obsługuje ponowne wysłanie EOT (po timeout EOT ACK).
     * Sprawdza limit prób retransmisji EOT.
     * Wywoływana przez `handleSendTimeout`.
     */
    private void handleEotRetry() {
        sendRetries++;
        System.out.println("Ponowna próba wysłania EOT. Próba: " + sendRetries + "/" + MAX_RETRIES);
        if (sendRetries >= MAX_RETRIES) {
            System.err.println("Przekroczono maksymalną liczbę prób wysłania EOT (brak ACK). Anuluję transfer.");
            abortTransfer(false);
        } else {
            sendEOT(); // Wyślij EOT ponownie (to ustawi stan SENDING_EOT i timeout EOT_ACK)
        }
    }


    // --- Metody Narzędziowe ---

    /**
     * Oblicza 8-bitową sumę kontrolną (algebraiczną) dla podanych danych.
     * @param data Dane do obliczenia sumy.
     * @return Obliczona suma kontrolna.
     */
    private static byte calculateChecksum(byte[] data) {
        byte checksum = 0;
        for (byte b : data) {
            checksum += b; // Suma algebraiczna, z naturalnym zawijaniem w zakresie bajtu
        }
        return checksum;
    }

    /**
     * Oblicza 16-bitowe CRC (CRC-16-CCITT Kermit/XMODEM: poly 0x1021, init 0x0000).
     * @param data Dane do obliczenia CRC.
     * @return Obliczone 16-bitowe CRC.
     */
    private static int calculateCRC16(byte[] data) {
        int crc = 0x0000; // Inicjalizacja dla XMODEM CRC
        int poly = 0x1021; // Generator wielomianu (CCITT)

        for (byte b : data) {
            crc ^= (int) b << 8; // XOR bajtu danych (przesuniętego do starszego bajtu CRC) z bieżącym CRC
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) { // Jeśli najstarszy bit CRC jest ustawiony
                    crc = (crc << 1) ^ poly; // Przesuń w lewo i XOR z wielomianem
                } else {
                    crc <<= 1; // W przeciwnym razie tylko przesuń w lewo
                }
            }
        }
        return crc & 0xFFFF; // Zwróć 16-bitową wartość
    }

    /**
     * Weryfikuje, czy dopełnienie numeru bloku jest poprawne.
     * @param blockNumber Numer bloku (0-255).
     * @param blockNumberComplement Dopełnienie numeru bloku (~blockNumber).
     * @return true, jeśli dopełnienie jest poprawne, false w przeciwnym razie.
     */
    public static boolean verifyBlockNumber(byte blockNumber, byte blockNumberComplement) {
        // Sprawdza, czy blockNumber + blockNumberComplement daje 0xFF (czyli 255 lub -1 dla bajtów ze znakiem)
        return (byte) (blockNumber + blockNumberComplement) == (byte) 0xFF;
    }

    /**
     * Zamyka otwarte zasoby (strumień pliku) i czyści dane pliku.
     * Wywoływana przy zakończeniu transferu (COMPLETED, ABORTED, ERROR).
     */
    private void closeResources() {
        // Zamknij plik wyjściowy (odbiornik)
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
                System.out.println("Zamknięto plik wyjściowy: " + outputFileName);
            } catch (IOException e) {
                System.err.println("Błąd podczas zamykania pliku wyjściowego: " + e.getMessage());
            }
            fileOutputStream = null;
        }
        // Wyczyść dane pliku (nadajnik)
        if (fileData != null) {
             // System.out.println("Czyszczenie danych pliku z pamięci.");
             fileData = null;
        }
    }

    /**
     * Metoda do zamknięcia wątku schedulera przy zamykaniu aplikacji.
     * Ważne, aby zapobiec zawieszeniu aplikacji.
     */
    public void shutdownScheduler() {
        System.out.println("Zamykanie wątku obsługi timeoutów Xmodem...");
        timeoutScheduler.shutdownNow(); // Natychmiast zatrzymaj oczekujące zadania
        try {
            // Poczekaj chwilę na zakończenie wątku
            if (!timeoutScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                System.err.println("Wątek timeoutów nie zakończył się w oczekiwanym czasie.");
            } else {
                 System.out.println("Wątek timeoutów zakończony.");
            }
        } catch (InterruptedException e) {
            System.err.println("Oczekiwanie na zamknięcie wątku timeoutów przerwane.");
            Thread.currentThread().interrupt();
        }
    }
}