package org.zespol;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Implementacja protokołu transferu plików XMODEM (z obsługą sumy kontrolnej i CRC-16).
 * Pozwala na wysyłanie i odbieranie plików przez port szeregowy przy użyciu
 * obiektu {@link SerialCommunicator}.
 */
public class Xmodem {

    private static final Logger LOGGER = Logger.getLogger(Xmodem.class.getName());

    // --- Stałe protokołu XMODEM ---
    private static final byte SOH = 0x01;    // Start of Header (początek bloku 128 bajtów danych)
    private static final byte EOT = 0x04;    // End of Transmission (koniec transmisji)
    private static final byte ACK = 0x06;    // Acknowledge (potwierdzenie odebrania bloku)
    private static final byte NAK = 0x15;    // Negative Acknowledge (żądanie retransmisji bloku)
    private static final byte CAN = 0x18;    // Cancel (anulowanie transmisji)
    private static final byte SUB = 0x1A;    // Substitute (znak wypełniający dane, Ctrl+Z)
    private static final byte CHAR_C = 0x43; // Znak 'C' (żądanie rozpoczęcia transmisji z CRC-16 przez odbiornik)

    // --- Konfiguracja parametrów transferu ---
    private static final int BLOCK_SIZE = 128; // Standardowy rozmiar bloku danych w bajtach
    private static final int MAX_RETRIES = 10;   // Maksymalna liczba prób retransmisji jednego bloku (nadajnik) / ponownego odbioru (odbiornik)
    private static final int MAX_INIT_RETRIES = 6; // Maksymalna liczba prób inicjalizacji (wysłania NAK/C przez odbiornik lub oczekiwania na NAK/C przez nadajnik)
    private static final long INIT_TIMEOUT_MS = 10000; // Czas (ms) oczekiwania na pierwszy blok (SOH) po wysłaniu NAK/C (odbiornik) lub na NAK/C od odbiornika (nadajnik)
    private static final long ACK_TIMEOUT_MS = 5000;  // Czas (ms) oczekiwania na ACK/NAK po wysłaniu bloku (nadajnik) lub na kolejny SOH/EOT po wysłaniu ACK (odbiornik)
    private static final long EOT_ACK_TIMEOUT_MS = 5000; // Czas (ms) oczekiwania na ACK po wysłaniu EOT (nadajnik)

    /**
     * Definiuje możliwe stany, w jakich może znajdować się proces transferu XMODEM.
     */
    // --- Stany transferu ---
    public enum TransferState {
        IDLE,           // Stan bezczynności, brak aktywnego transferu

        // --- Stany Odbiornika ---
        RECEIVER_INIT,  // Odbiornik: Inicjalizacja, wysyłanie sygnału NAK lub 'C'
        EXPECTING_SOH,  // Odbiornik: Oczekiwanie na nagłówek bloku (SOH) lub koniec transmisji (EOT)
        RECEIVING,      // Odbiornik: Aktywne przetwarzanie odebranego bloku danych (stan przejściowy)

        // --- Stany Nadajnika ---
        SENDER_WAIT_INIT, // Nadajnik: Oczekiwanie na sygnał inicjujący NAK lub 'C' od odbiornika
        SENDING,        // Nadajnik: Wysyłanie bieżącego bloku danych
        WAITING_FOR_ACK,// Nadajnik: Oczekiwanie na potwierdzenie (ACK) lub żądanie retransmisji (NAK)
        SENDING_EOT,    // Nadajnik: Wysyłanie sygnału końca transmisji (EOT)
        WAITING_FOR_EOT_ACK, // Nadajnik: Oczekiwanie na ostateczne potwierdzenie (ACK) po wysłaniu EOT

        // --- Stany Końcowe ---
        COMPLETED,      // Transfer zakończony pomyślnie
        ABORTED,        // Transfer przerwany (przez sygnał CAN lub przekroczenie limitu błędów)
        ERROR           // Wystąpił nieoczekiwany błąd krytyczny (np. błąd I/O)
    }

    // --- Pola instancji klasy ---
    private volatile TransferState currentState; // Bieżący stan automatu skończonego (volatile dla bezpieczeństwa wątkowego - zapis odczyt zawsze do RAM a nie cache)
    private final SerialCommunicator communicator; // Obiekt odpowiedzialny za fizyczną komunikację przez port szeregowy
    private final List<Byte> receiveBuffer = new ArrayList<>(); // Bufor na dane odbierane z portu szeregowego, przetwarzane przez processInternalBuffer
    private String outputFileName; // Nazwa pliku, do którego zapisywane są odbierane dane (używane przez odbiornik)
    private FileOutputStream fileOutputStream; // Strumień do zapisu danych do pliku wyjściowego (odbiornik)
    private volatile boolean fileStreamClosed = false; // Flaga wskazująca, czy strumień do pliku wyjściowego został już zamknięty
    private boolean useCRC = false; // Flaga określająca, czy używany jest tryb CRC-16 (true) czy suma kontrolna (false)

    private int expectedBlockNumber = 1; // Numer kolejnego oczekiwanego bloku danych (odbiornik, zaczyna od 1)
    private int receiveRetries = 0; // Licznik prób odbioru bieżącego bloku lub prób inicjalizacji (odbiornik)
    private int sendRetries = 0; // Licznik prób wysłania bieżącego bloku lub EOT, lub prób inicjalizacji (nadajnik)

    // Wątek do obsługi timeoutów
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeoutTaskHandler; // Uchwyt do aktywnego zadania obsługi timeoutu

    // Zmienne specyficzne dla nadajnika
    private byte[] fileData; // Zawartość pliku do wysłania, załadowana do pamięci
    private int currentBlockIndex = 0; // Indeks (0-based) bieżącego bloku danych do wysłania

    /**
     * Konstruktor klasy Xmodem.
     *
     * @param communicator Obiekt {@link SerialCommunicator} do obsługi komunikacji szeregowej.
     */
    public Xmodem(SerialCommunicator communicator) {
        this.communicator = communicator;
        if (this.communicator == null) {
            LOGGER.log(Level.SEVERE, "SerialCommunicator nie może być null!");
            throw new IllegalArgumentException("SerialCommunicator nie może być null");
        }
        this.currentState = TransferState.IDLE;
    }

    /**
     * Ustawia nazwę pliku wyjściowego dla odbieranych danych.
     * Powinno być wywołane przed rozpoczęciem odbioru.
     *
     * @param fileName Nazwa pliku do zapisu.
     */
    // --- Gettery / Settery ---
    public void setOutputFileName(String fileName) {
        this.outputFileName = fileName;
    }

    /**
     * Zwraca bieżący stan transferu XMODEM.
     *
     * @return Aktualny stan z enum {@link TransferState}.
     */
    public TransferState getCurrentState() {
        return currentState;
    }

    // =========================================================================
    // --- Metody Odbiornika ---
    // =========================================================================

    /**
     * Inicjuje proces odbierania pliku w trybie XMODEM.
     * Ustawia stan początkowy i wysyła pierwszy sygnał NAK lub 'C'.
     *
     * @param useCRC Jeśli true, odbiornik zażąda transferu z CRC-16 (wysyłając 'C').
     *               Jeśli false, zażąda transferu ze standardową sumą kontrolną (wysyłając NAK).
     */
    public void startReceive(boolean useCRC) {
        if (currentState != TransferState.IDLE) {
            LOGGER.warning("Próba rozpoczęcia odbioru, gdy transfer jest już w toku lub nie został poprawnie zakończony. Stan: " + currentState);
            return; // Można rozważyć rzucenie wyjątku
        }
        if (outputFileName == null || outputFileName.isEmpty()) {
            LOGGER.severe("Nie ustawiono nazwy pliku wyjściowego przed rozpoczęciem odbioru.");
            changeState(TransferState.ERROR);
            return;
        }

        LOGGER.info("Rozpoczynanie odbioru pliku: " + outputFileName + " (CRC: " + useCRC + ")");
        this.useCRC = useCRC;
        this.expectedBlockNumber = 1;
        this.receiveRetries = 0;
        this.receiveBuffer.clear();
        this.fileStreamClosed = true; // Zakładamy, że jest zamknięty, dopóki nie otworzymy
        this.fileOutputStream = null; // Upewnij się, że jest null na start

        // Zmień stan na inicjalizację odbiornika
        changeState(TransferState.RECEIVER_INIT);
        // Wyślij pierwszy sygnał NAK lub 'C' i ustaw timeout
        initiateTransferSignal();
    }


    /**
     * Wysyła początkowy sygnał NAK lub 'C' do nadajnika, aby zainicjować transfer.
     * Ustawia również timeout oczekiwania na pierwszy blok danych (SOH).
     * Metoda wywoływana wewnętrznie podczas inicjalizacji odbioru (stan RECEIVER_INIT).
     */
    private void initiateTransferSignal() {
        if (currentState != TransferState.RECEIVER_INIT) return;

        if (receiveRetries >= MAX_INIT_RETRIES) {
            LOGGER.severe("Przekroczono limit prób inicjalizacji odbioru (" + MAX_INIT_RETRIES + "). Anulowanie transferu.");
            abortTransfer(false); // Anuluj transfer lokalnie
            return;
        }

        byte signal = useCRC ? CHAR_C : NAK;
        String signalName = useCRC ? "'C'" : "NAK";
        LOGGER.info("[Odbiornik] Wysyłanie sygnału inicjującego " + signalName + " (próba " + (receiveRetries + 1) + "/" + MAX_INIT_RETRIES + ")");
        communicator.sendData(new byte[]{signal});
        receiveRetries++;

        // Ustaw timeout oczekiwania na pierwszy SOH
        cancelTimeoutTask(); // Anuluj poprzedni, jeśli istnieje
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleReceiveTimeout, INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Zmień stan na oczekiwanie na SOH (lub EOT, choć EOT na starcie jest błędem)
        changeState(TransferState.EXPECTING_SOH);
    }

    /**
     * Obsługuje sytuację timeoutu podczas odbioru danych.
     * Wywoływana przez {@link ScheduledExecutorService}, gdy upłynie czas oczekiwania.
     * W zależności od stanu, ponawia próbę inicjalizacji (wysyłając NAK/C)
     * lub żąda retransmisji ostatniego bloku (wysyłając NAK).
     */
    private void handleReceiveTimeout() {
        if (currentState == TransferState.EXPECTING_SOH) {
            // Timeout podczas oczekiwania na SOH lub EOT
            if (expectedBlockNumber == 1 && receiveRetries < MAX_INIT_RETRIES) {
                // Jeśli to timeout oczekiwania na pierwszy blok, ponów wysłanie NAK/C
                LOGGER.warning("[Odbiornik] Timeout oczekiwania na pierwszy blok (SOH). Ponawiam sygnał inicjujący.");
                changeState(TransferState.RECEIVER_INIT); // Wróć do stanu inicjalizacji
                initiateTransferSignal(); // Wyślij NAK/C ponownie
            } else if (expectedBlockNumber > 1 && receiveRetries < MAX_RETRIES) {
                // Jeśli to timeout oczekiwania na kolejny blok (po poprawnym odebraniu poprzednich)
                LOGGER.warning("[Odbiornik] Timeout oczekiwania na blok " + expectedBlockNumber + " (lub EOT). Wysyłam NAK.");
                receiveRetries++;
                sendNak(); // Wyślij NAK, żądając retransmisji
                // Stan pozostaje EXPECTING_SOH, resetujemy timeout w sendNak()
            } else {
                // Przekroczono limit prób
                LOGGER.severe("[Odbiornik] Przekroczono limit (" + (expectedBlockNumber == 1 ? MAX_INIT_RETRIES : MAX_RETRIES) + ") prób oczekiwania/odbioru. Anulowanie transferu.");
                abortTransfer(false);
            }
        } else {
            LOGGER.warning("Timeout wystąpił w nieoczekiwanym stanie odbiornika: " + currentState);
        }
        // Timeouty w innych stanach (np. IDLE, COMPLETED) są ignorowane
    }

    /**
     * Metoda publiczna wywoływana przez zewnętrzny komponent (np. listener portu szeregowego),
     * gdy nowe dane zostaną odebrane z portu szeregowego.
     * Dane są dodawane do wewnętrznego bufora, a następnie wywoływana jest metoda przetwarzająca bufor.
     *
     * @param data Tablica bajtów odebrana z portu szeregowego.
     */
    public void ReceivedDataFromSerial(byte[] data) {
        if (data == null || data.length == 0) {
            return; // Ignoruj puste dane
        }

        // Synchronizacja na buforze jest ważna, aby uniknąć ConcurrentModificationException,
        // gdy wątek listenera portu dodaje dane, a wątek przetwarzający (lub timeout) może je modyfikować.
        // Nakładana jest blokada wyłączna na buforze.

        // Dodaj odebrane bajty do wewnętrznego bufora
        // Synchronizacja jest potrzebna, jeśli ta metoda może być wywołana z innego wątku niż processInternalBuffer
        synchronized (receiveBuffer) {
            for (byte b : data) {
                receiveBuffer.add(b);
            }
        }

        // Uruchom przetwarzanie danych w buforze
        processInternalBuffer();
    }

    /**
     * Przetwarza dane zgromadzone w wewnętrznym buforze (`receiveBuffer`).
     * Działa w pętli, próbując zinterpretować i przetworzyć jednostki protokołu XMODEM
     * (pojedyncze bajty sterujące jak EOT, ACK, NAK, CAN lub kompletne bloki danych SOH)
     * znajdujące się na początku bufora.
     * Metoda jest `synchronized`, aby zapobiec współbieżnemu dostępowi do bufora i stanu.
     */
    private synchronized void processInternalBuffer() {

        while (!receiveBuffer.isEmpty()) {
            // Sprawdź pierwszy bajt w buforze bez usuwania go
            byte firstByte = receiveBuffer.getFirst();

            switch (currentState) {
                case EXPECTING_SOH:
                    // W tym stanie oczekujemy SOH (start bloku) lub EOT (koniec transmisji)
                    if (firstByte == SOH) {
                        // Potencjalny początek bloku danych
                        int requiredBlockLength = 3 + BLOCK_SIZE + (useCRC ? 2 : 1); // SOH, Nr, ~Nr, Dane[128], Cksum/CRC[1/2]
                        if (receiveBuffer.size() >= requiredBlockLength) {
                            // Mamy wystarczająco danych na kompletny blok
                            byte[] block = extractBytesFromBuffer(requiredBlockLength);
                            if (block.length == requiredBlockLength) {
                                cancelTimeoutTask(); // Odebraliśmy coś, anuluj timeout
                                processXmodemBlock(block); // Przetwórz blok
                            } else {
                                LOGGER.warning("Błąd podczas wyciągania bloku SOH z bufora.");
                                return; // Zakończ przetwarzanie na razie
                            }
                        } else {
                            // Za mało danych na pełny blok, czekaj na więcej
                            // Wykryto SOH, ale dane są niekompletne, czekamy na resztę
                            return; // Zakończ pętlę, poczekaj na więcej danych
                        }
                    } else if (firstByte == EOT) {
                        // Koniec transmisji
                        byte[] eot = extractBytesFromBuffer(1);
                        if (eot.length == 1) {
                            cancelTimeoutTask(); // Odebraliśmy EOT, anuluj timeout
                            LOGGER.info("[Odbiornik] Odebrano EOT.");
                            completeTransfer(); // Zakończ transfer
                        } else {
                            LOGGER.warning("Błąd podczas wyciągania EOT z bufora.");
                            return; // Zakończ przetwarzanie
                        }
                    } else if (firstByte == CAN) {
                        // Anulowanie transmisji przez drugą stronę
                        byte[] can = extractBytesFromBuffer(1);
                        if (can.length == 1) {
                            cancelTimeoutTask();
                            LOGGER.warning("[Odbiornik] Odebrano CAN. Anulowanie transferu.");
                            abortTransfer(true); // Anuluj transfer (zdalnie zainicjowany)
                        } else {
                             LOGGER.warning("Błąd podczas wyciągania CAN z bufora.");
                             return;
                        }
                    } else {
                        // Nieoczekiwany bajt w tym stanie
                        byte unexpectedByte = extractBytesFromBuffer(1)[0];
                        LOGGER.warning("[Odbiornik] W stanie " + currentState + " odebrano nieoczekiwany bajt: " + String.format("0x%02X", unexpectedByte) + ". Ignorowanie.");
                        // Ignorujemy nieoczekiwane bajty, czekając na SOH lub EOT
                    }
                    break; // Koniec obsługi stanu EXPECTING_SOH

                case SENDER_WAIT_INIT:
                case WAITING_FOR_ACK:
                case WAITING_FOR_EOT_ACK:
                    // Stany nadajnika - odbieranie danych (NAK, 'C', ACK, CAN)
                    handleSenderReceivedByte(firstByte);
                    // Jeśli bajt został przetworzony (np. ACK), extractBytesFromBuffer zostanie wywołane w handleSenderReceivedByte
                    // Jeśli nie został przetworzony lub potrzeba więcej danych, pętla zakończy się lub będzie kontynuowana
                    break;

                case IDLE:
                case RECEIVER_INIT:
                case RECEIVING: // Stan przejściowy, nie powinniśmy tu odbierać nowych danych z bufora
                case SENDING:
                case SENDING_EOT:
                case COMPLETED:
                case ABORTED:
                case ERROR:
                    // W tych stanach generalnie nie oczekujemy nowych danych z bufora lub transfer jest zakończony/anulowany
                    byte ignoredByte = extractBytesFromBuffer(1)[0];
                    LOGGER.finer("Ignorowanie bajtu " + String.format("0x%02X", ignoredByte) + " w stanie " + currentState);
                    // Ignorujemy inne bajty i czekamy na timeout EOT lub poprawny ACK.
                    break; // Ignoruj dane w tych stanach

                default:
                    // Nieznany stan
                    byte unknownStateByte = extractBytesFromBuffer(1)[0];
                    LOGGER.severe("Nieobsługiwany stan: " + currentState + ". Ignorowanie bajtu: " + String.format("0x%02X", unknownStateByte));
                    abortTransfer(false);
                    return; // Zakończ przetwarzanie
            }

            // Jeśli stan się zmienił lub przetworzono dane, pętla while będzie kontynuowana
            // Jeśli czekamy na więcej danych (np. niepełny blok SOH), pętla zakończy się przez 'return' w odpowiednim case
        }
    }


    /**
     * Pomocnicza metoda do wyciągania (i usuwania) określonej liczby bajtów z początku bufora `receiveBuffer`.
     * Ta metoda musi być wywoływana z kontekstu, który zapewnia synchronizację dostępu do `receiveBuffer`
     * (np. z metody `synchronized` lub w bloku `synchronized(receiveBuffer)`).
     *
     * @param count Liczba bajtów do wyciągnięcia.
     * @return Tablica `byte[]` zawierająca wyciągnięte bajty. Zwraca pustą tablicę, jeśli `count` jest niepoprawny
     *         lub jeśli bufor nie zawiera wystarczającej liczby bajtów (co nie powinno się zdarzyć przy poprawnym użyciu).
     */
    private byte[] extractBytesFromBuffer(int count) {
        if (count <= 0 || count > receiveBuffer.size()) {
            LOGGER.warning("Próba wyciągnięcia nieprawidłowej liczby bajtów (" + count + ") z bufora (rozmiar: " + receiveBuffer.size() + ")");
            return new byte[0]; // Zwróć pustą tablicę w przypadku błędu
        }

        byte[] extracted = new byte[count];
        // Skuteczniejszy sposób kopiowania i usuwania z ArrayList
        for (int i = 0; i < count; i++) {
            extracted[i] = receiveBuffer.removeFirst(); // Usuń i pobierz pierwszy element
        }
        return extracted;
    }


    /**
     * Przetwarza pojedynczy, kompletny blok danych XMODEM (rozpoczynający się od SOH).
     * Weryfikuje numer bloku, jego dopełnienie, sumę kontrolną/CRC i sekwencję.
     * Jeśli blok jest poprawny i oczekiwany, zapisuje dane do pliku i wysyła ACK.
     * Jeśli blok jest duplikatem poprzedniego, wysyła ACK.
     * W przypadku błędów wysyła NAK lub anuluje transfer.
     * Wywoływana przez {@link #processInternalBuffer}, gdy zidentyfikuje kompletny blok SOH.
     *
     * @param block Tablica bajtów zawierająca kompletny blok XMODEM:
     *              [SOH, NumerBloku, ~NumerBloku, Dane[128], SumaKontrolna] lub
     *              [SOH, NumerBloku, ~NumerBloku, Dane[128], CRC-High, CRC-Low]
     */
    private void processXmodemBlock(byte[] block) {
        // Ustawienie stanu na RECEIVING na czas przetwarzania tego bloku
        changeState(TransferState.RECEIVING);

        // Zakłada, że block[0] == SOH (weryfikacja w processInternalBuffer)
        byte blockNumberByte = block[1];
        byte blockNumberComplement = block[2];
        // Konwersja numeru bloku na int bez znaku (0-255)
        int blockNumber = blockNumberByte & 0xFF;

        System.out.print("\n[Odbiornik] Przetwarzanie bloku SOH, Nr: " + blockNumber); // Logowanie konsolowe dla dewelopera

        // --- Krok 1: Weryfikacja numeru bloku i jego dopełnienia bitowego ---
        if (!verifyBlockNumber(blockNumberByte, blockNumberComplement)) {
            // Numer bloku i jego dopełnienie bitowe (one's complement) nie pasują do siebie.
            LOGGER.warning(". Błąd: Niezgodny numer bloku (" + blockNumber + ") i dopełnienie (" + String.format("%02X", blockNumberComplement) + "). Oczekiwano dopełnienia: " + String.format("%02X", (byte) (255 - blockNumberByte)) + ".");
            System.out.println(". Błąd: Niezgodny numer bloku (" + blockNumber + ") i dopełnienie (" + String.format("%02X", blockNumberComplement) + "). Oczekiwano: " + String.format("%02X", (byte) (255 - blockNumberByte)) + ".");
            // Obsługa błędu - wysłanie NAK, ustawienie stanu i timeoutu
            handleBlockError();
            return; // Zakończ przetwarzanie tego bloku
        }

        // --- Krok 2: Weryfikacja sekwencji bloków (oczekiwany vs duplikat) ---
        // Numer oczekiwanego bloku (z zawijaniem modulo 256)
        // 2. Sprawdź, czy to jest oczekiwany numer bloku lub duplikat poprzedniego
        int expectedNum = expectedBlockNumber % 256;
        // Numer poprzedniego bloku (na wypadek retransmisji po utraconym ACK)
        int previousNum = (expectedBlockNumber == 1) ? 0 : (expectedBlockNumber - 1 + 256) % 256; // Poprawka dla pierwszego bloku

        if (blockNumber == expectedNum) {
            // To jest poprawny, oczekiwany blok.
            System.out.print(". Oczekiwany numer (" + expectedNum + "). ");

            // Wyodrębnij dane użytkownika (payload)
            byte[] payload = Arrays.copyOfRange(block, 3, 3 + BLOCK_SIZE);
            boolean checksumOk; // Flaga poprawności sumy kontrolnej / CRC

            // --- Krok 3: Weryfikacja sumy kontrolnej lub CRC ---
            if (useCRC) {
                // Krok 3a: Weryfikacja sumy kontrolnej CRC-16
                // Odczytaj CRC z dwóch ostatnich bajtów bloku (Big Endian)
                int receivedCRC = ((block[3 + BLOCK_SIZE] & 0xFF) << 8) | (block[3 + BLOCK_SIZE + 1] & 0xFF);
                // Oblicz CRC dla otrzymanego payloadu
                int calculatedCRC = calculateCRC16(payload);
                System.out.print("CRC Odb: " + String.format("%04X", receivedCRC) + ", Oblicz: " + String.format("%04X", calculatedCRC) + ". ");
                // Porównaj otrzymane i obliczone CRC
                checksumOk = (receivedCRC == calculatedCRC);
            } else {
                // Krok 3b: Weryfikacja prostej sumy kontrolnej
                // Odczytaj sumę kontrolną z ostatniego bajtu bloku
                byte receivedChecksum = block[3 + BLOCK_SIZE];
                // Oblicz sumę kontrolną dla otrzymanego payloadu
                byte calculatedChecksum = calculateChecksum(payload);
                System.out.print("Suma Odb: " + String.format("%02X", receivedChecksum) + ", Oblicz: " + String.format("%02X", calculatedChecksum) + ". ");
                // Porównaj otrzymaną i obliczoną sumę
                checksumOk = (receivedChecksum == calculatedChecksum);
            }

            // --- Krok 4: Przetwarzanie wyniku weryfikacji sumy/CRC ---
            if (checksumOk) {
                // Suma kontrolna / CRC jest poprawna.
                System.out.println("Suma kontrolna poprawna. Zapisuję dane.");
                // Zapisz payload do pliku
                if (savePayloadToFile(payload)) {
                    // Zapis powiódł się.
                    // Przygotowanie na odbiór następnego bloku
                    expectedBlockNumber++;
                    // Zresetowanie licznika prób odbioru dla nowego bloku
                    receiveRetries = 0;
                    // Wysłanie potwierdzenia ACK
                    sendAck();
                    // Ustawienie stanu oczekiwania na kolejny pakiet
                    changeState(TransferState.EXPECTING_SOH);
                    // Resetowanie timeoutu oczekiwania na pakiet
                    resetReceiveTimeout();
                } else {
                    // Obsługa krytycznego błędu zapisu pliku
                    LOGGER.severe("Krytyczny błąd zapisu do pliku '" + outputFileName + "'! Anuluję transfer.");
                    // Błąd zapisu pliku - traktujemy jak krytyczny błąd
                    System.err.println("Krytyczny błąd zapisu do pliku! Anuluję transfer.");
                    // Anuluj transfer z inicjatywy lokalnej
                    abortTransfer(false);
                }
            } else {
                // Suma kontrolna / CRC jest niepoprawna.
                LOGGER.warning("Błąd sumy kontrolnej/CRC dla bloku " + blockNumber);
                System.out.println("Błąd sumy kontrolnej.");
                // Obsługa błędu sumy kontrolnej - wysłanie NAK
                handleBlockError();
            }

        } else if (blockNumber == previousNum && expectedBlockNumber > 1) {
            // Otrzymano duplikat poprzedniego bloku (prawdopodobnie utracono ACK). Sprawdzamy expected > 1, aby uniknąć pętli przy bloku 0/255.
            LOGGER.info(". Duplikat bloku " + blockNumber + " (oczekiwano " + expectedNum + "). Wysyłam ponownie ACK.");
            System.out.println(". Duplikat bloku " + blockNumber + " (oczekiwano " + expectedNum + "). Wysyłam ponownie ACK.");
            // Wysłanie ponownego ACK dla zduplikowanego bloku
            sendAck();
            // Ważne: Nie zapisywać danych i nie zmieniać oczekiwanego numeru bloku
            // Pozostanie w stanie oczekiwania na właściwy blok
            changeState(TransferState.EXPECTING_SOH);
            // Resetowanie timeoutu po wysłaniu ACK
            resetReceiveTimeout();

        } else {
            // Krytyczny błąd sekwencji - otrzymano nieoczekiwany numer bloku
            LOGGER.severe(". KRYTYCZNY BŁĄD SEKWENCJI! Oczekiwano bloku " + expectedNum + " (lub duplikatu " + previousNum + "), a otrzymano " + blockNumber + ". Anuluję transfer.");
            // Błąd sekwencji bloków - nie jest to ani oczekiwany, ani poprzedni blok. Poważny problem.
            System.err.println(". KRYTYCZNY BŁĄD SEKWENCJI! Oczekiwano bloku " + expectedNum + " lub " + previousNum + ", otrzymano " + blockNumber + ". Anuluję transfer.");
            // Anulowanie transferu z powodu błędu sekwencji
            abortTransfer(false);
        }
    }

    /**

     * Resetuje timeout oczekiwania na następny pakiet (SOH lub EOT) od nadajnika.
     * Używa stałej {@link #ACK_TIMEOUT_MS}, zakładając, że po wysłaniu ACK przez odbiornik,
     * czas oczekiwania na odpowiedź nadajnika jest podobny do czasu oczekiwania nadajnika na ACK.
     * Wywoływana po wysłaniu ACK lub NAK.
     */
    private void resetReceiveTimeout() {
        cancelTimeoutTask(); // Anuluj poprzedni timeout, jeśli istnieje
        // Ustaw nowy timeout oczekiwania na SOH/EOT
        // System.out.println("Resetuję timeout odbiornika (" + ACK_TIMEOUT_MS + "ms) na SOH/EOT.");
        // Używamy krótszego timeoutu, gdy już trwa transmisja
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleReceiveTimeout, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Zapisuje blok danych (payload) do otwartego pliku wyjściowego.
     * Tworzy i otwiera strumień pliku przy pierwszym zapisie.
     * Ta metoda *nie* usuwa znaków wypełnienia SUB (0x1A) z danych;
     * usuwanie paddingu odbywa się jednorazowo po zakończeniu transferu.
     *
     * @param payload Tablica bajtów (128) zawierająca dane do zapisania.
     * @return {@code true} jeśli zapis się powiódł, {@code false} w przypadku błędu I/O.
     */
    private boolean savePayloadToFile(byte[] payload) {
        try {
            // Otwórz strumień pliku, jeśli jeszcze nie jest otwarty
        // Sprawdź, czy strumień jest dostępny i nie został zamknięty
            if (fileOutputStream == null) {
                LOGGER.info("Otwieranie strumienia do pliku: " + outputFileName);
                fileOutputStream = new FileOutputStream(outputFileName, false); // false = nadpisz, jeśli istnieje
                fileStreamClosed = false; // Strumień jest teraz otwarty
            }
            // Zapisz dane do strumienia
            fileOutputStream.write(payload);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Błąd I/O podczas zapisu do pliku: " + outputFileName, e);
            // W przypadku błędu zapisu, zamknij strumień, jeśli był otwarty
            closeOutputFileStream();
            changeState(TransferState.ERROR); // Ustaw stan błędu
            // Błąd zapisu jest krytyczny, ale decyzję o anulowaniu podejmuje processXmodemBlock/handleBlockError
            return false;
        }
    }


    /**
     * Wysyła bajt potwierdzenia ACK (0x06) do nadajnika.
     */
    private void sendAck() {
        communicator.sendData(new byte[]{ACK});
    }

    /**
     * Wysyła bajt negatywnego potwierdzenia NAK (0x15) do nadajnika,
     * sygnalizując błąd i żądając retransmisji ostatniego bloku.
     * Resetuje również timeout oczekiwania na odpowiedź.
     */
    // Wysyła NAK
    private void sendNak() {
        LOGGER.info("[Odbiornik] Wysyłanie NAK (próba błędu " + receiveRetries + ")");
        communicator.sendData(new byte[]{NAK});
        // Po wysłaniu NAK, musimy zresetować timeout oczekiwania na ponowne wysłanie bloku
        resetReceiveTimeout();
        // Ustawiamy stan na oczekiwanie SOH
        changeState(TransferState.EXPECTING_SOH);
        // Timeout jest resetowany przez metodę wywołującą (np. handleBlockError).
    }

    /**
     * Obsługuje błąd wykryty podczas przetwarzania bloku danych (np. zła suma kontrolna,
     * nieprawidłowy numer bloku lub jego dopełnienie).
     * Inkrementuje licznik błędów. Jeśli limit prób nie został przekroczony, wysyła NAK.
     * W przeciwnym razie anuluje transfer.
     * Wywoływana przez {@link #processXmodemBlock}.
     */
    private void handleBlockError() {
        receiveRetries++;
        LOGGER.warning("Błąd odbioru bloku " + (expectedBlockNumber % 256) + ". Próba " + receiveRetries + "/" + MAX_RETRIES);
        if (receiveRetries >= MAX_RETRIES) {
            LOGGER.severe("Przekroczono maksymalną liczbę (" + MAX_RETRIES + ") prób odbioru bloku. Anulowanie transferu.");
            abortTransfer(false); // Anuluj transfer lokalnie
        } else {
            sendNak(); // Wyślij NAK, żądając retransmisji
        }
    }

    /**
     * Finalizuje proces odbierania pliku po otrzymaniu sygnału EOT (End of Transmission).
     * Wysyła ostatnie potwierdzenie ACK, zamyka plik wyjściowy, usuwa z niego
     * ewentualne końcowe znaki wypełnienia (SUB), i ustawia stan na COMPLETED.
     * Wywoływana przez {@link #processInternalBuffer} po odebraniu EOT.
     */
    private void completeTransfer() {
        LOGGER.info("Transfer pliku zakończony (odebrano EOT). Wysyłanie końcowego ACK.");
        sendAck(); // Wyślij ostatnie ACK jako potwierdzenie odbioru EOT

        // Zamknij strumień pliku, jeśli był otwarty
        closeOutputFileStream();

        // Usuń końcowe bajty SUB (padding) z pliku, jeśli został utworzony
        // --- Zamykanie pliku i usuwanie paddingu SUB ---
        if (outputFileName != null && !outputFileName.isEmpty()) {
             try {
                 removeTrailingSubPadding();

             } catch (IOException e) {
                 LOGGER.log(Level.SEVERE, "Nie udało się usunąć końcowego paddingu z pliku: " + outputFileName, e);
                 // Mimo błędu usuwania paddingu, transfer uznajemy za zakończony, ale logujemy problem
             }
        }

        LOGGER.info("Transfer zakończony pomyślnie: " + outputFileName);
        changeState(TransferState.COMPLETED); // Ustaw stan końcowy
        // Nie ma potrzeby resetować timeoutu, transfer zakończony
    }

    /**
     * Usuwa końcowe bajty wypełnienia SUB (0x1A) z zapisanego pliku.
     * Odczytuje plik od końca, szukając ostatniego bajtu różnego od SUB,
     * a następnie skraca plik do tej pozycji.
     * Wywoływana przez {@link #completeTransfer} PO zamknięciu strumienia zapisu.
     *
     * @throws IOException Jeśli wystąpi błąd podczas operacji na pliku.
     */
    private void removeTrailingSubPadding() throws IOException {
        if (fileOutputStream != null || !fileStreamClosed) {
             LOGGER.warning("Próba usunięcia paddingu przed zamknięciem strumienia pliku!");
             closeOutputFileStream(); // Upewnij się, że jest zamknięty
        }
        if (outputFileName == null || Files.notExists(Paths.get(outputFileName))) {
             LOGGER.info("Plik wyjściowy nie istnieje lub nie został utworzony, pomijanie usuwania paddingu.");
             return; // Nie ma pliku, nie ma co usuwać
        }

        LOGGER.info("Usuwanie końcowego paddingu SUB (0x1A) z pliku: " + outputFileName);
        long originalSize = Files.size(Paths.get(outputFileName));
        long newSize = originalSize;

        try (RandomAccessFile raf = new RandomAccessFile(outputFileName, "rw")) {
            // Jeśli plik jest pusty, nie ma co robić
             if (originalSize == 0) {
                 LOGGER.info("Plik jest pusty, brak paddingu do usunięcia.");
                 return;
             }

            // Przeszukuj od końca pliku
            for (long pos = originalSize - 1; pos >= 0; pos--) {
                raf.seek(pos);
                int b = raf.read();
                if (b == -1) { // Koniec pliku (nie powinno się zdarzyć w pętli)
                    break;
                }
                if ((byte) b != SUB) {
                    // Znaleziono pierwszy bajt od końca, który nie jest SUB
                    newSize = pos + 1;
                    break;
                }
                // Jeśli bajt to SUB, kontynuuj szukanie wstecz
                newSize = pos; // Aktualizuj potencjalny nowy rozmiar (jeśli cały plik to SUB)
            }

            // Jeśli nowy rozmiar jest mniejszy niż obecny, obetnij plik
            if (newSize < originalSize) {
                raf.setLength(newSize); // Skróć plik do nowego rozmiaru
                LOGGER.info("Usunięto " + (originalSize - newSize) + " bajtów paddingu. Nowy rozmiar pliku: " + newSize);
            } else {
                LOGGER.info("Nie znaleziono końcowego paddingu SUB do usunięcia.");
            }
        }
    }



    /**
     * Anuluje bieżący transfer XMODEM.
     * W zależności od przyczyny, może wysłać sygnał CAN do drugiej strony.
     * Zmienia stan na ABORTED, czyści bufor odbiorczy, zamyka plik (jeśli otwarty)
     * i anuluje ewentualne aktywne zadanie timeoutu.
     *
     * @param remoteInitiated {@code true}, jeśli anulowanie zostało zainicjowane przez
     *                        odebranie sygnału CAN od drugiej strony; {@code false}, jeśli
     *                        anulowanie jest inicjowane lokalnie (np. przez błąd, timeout).
     */
    void abortTransfer(boolean remoteInitiated) {
        if (currentState == TransferState.ABORTED || currentState == TransferState.COMPLETED || currentState == TransferState.ERROR) {
            return; // Już w stanie końcowym lub błędzie
        }

        LOGGER.warning("Anulowanie transferu. Inicjowane zdalnie: " + remoteInitiated + ", Stan bieżący: " + currentState);
        cancelTimeoutTask(); // Anuluj aktywny timeout

        if (!remoteInitiated && currentState != TransferState.IDLE) {
            // Jeśli anulowanie jest lokalne i transfer był w toku, wyślij CAN (dwa razy dla pewności)
            LOGGER.info("Wysyłanie sygnału CAN.");
            // Wyślij CAN dwukrotnie dla pewności, jeśli my anulujemy
            byte[] cancelSignal = {CAN, CAN};
            communicator.sendData(cancelSignal);
        }

        changeState(TransferState.ABORTED); // Ustaw stan anulowania

        // Czyszczenie zasobów
        receiveBuffer.clear(); // Wyczyść bufor odbiorczy
        closeOutputFileStream(); // Zamknij plik wyjściowy, jeśli był otwarty
        fileData = null; // Wyczyść dane pliku do wysłania (jeśli dotyczy nadajnika)

        LOGGER.info("Transfer anulowany.");
    }

    /**
     * Bezpiecznie zamyka strumień do pliku wyjściowego, jeśli jest otwarty.
     * Ustawia flagę {@code fileStreamClosed} na true.
     * Łapie i loguje ewentualne wyjątki IOException.
     */
    private void closeOutputFileStream() {
        if (fileOutputStream != null && !fileStreamClosed) {
            try {
                LOGGER.info("Zamykanie strumienia pliku: " + outputFileName);
                fileOutputStream.flush(); // Upewnij się, że wszystko zostało zapisane
                fileOutputStream.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Błąd podczas zamykania strumienia pliku: " + outputFileName, e);
                // Mimo błędu zamknięcia, uznajemy strumień za nieaktywny
            } finally {
                fileOutputStream = null;
                fileStreamClosed = true;
            }
        } else {
             fileStreamClosed = true; // Upewnij się, że flaga jest true
        }
    }


    /**
     * Anuluje bieżące zaplanowane zadanie timeoutu, jeśli istnieje i nie zostało jeszcze wykonane lub anulowane.
     */
    private void cancelTimeoutTask() {
        if (timeoutTaskHandler != null && !timeoutTaskHandler.isDone()) {
            timeoutTaskHandler.cancel(false); // false - nie przerywaj, jeśli już działa (co nie powinno mieć miejsca dla timeoutu)
            timeoutTaskHandler = null;
        }
    }


    // =========================================================================
    // --- Metody Nadajnika ---
    // =========================================================================

    /**
     * Rozpoczyna proces wysyłania pliku w trybie XMODEM.
     * Odczytuje plik do pamięci, ustawia stan oczekiwania na inicjalizację
     * przez odbiornik (NAK lub 'C') i uruchamia timeout.
     *
     * @param filePath Ścieżka do pliku, który ma zostać wysłany.
     * @param useCRC   Jeśli true, nadajnik będzie oczekiwał na sygnał 'C' i użyje CRC-16.
     *                 Jeśli false, będzie oczekiwał na NAK i użyje sumy kontrolnej.
     */
    public void startSend(String filePath, boolean useCRC) {
        if (currentState != TransferState.IDLE) {
            LOGGER.warning("Próba rozpoczęcia wysyłania, gdy transfer jest już w toku. Stan: " + currentState);
            return;
        }

        LOGGER.info("Rozpoczynanie wysyłania pliku: " + filePath + " (CRC: " + useCRC + ")");
        this.useCRC = useCRC;
        this.currentBlockIndex = 0;
        this.sendRetries = 0;
        this.receiveBuffer.clear();

        // Wczytaj plik do pamięci
        try {
            fileData = Files.readAllBytes(Paths.get(filePath));
            if (fileData.length == 0) {
                LOGGER.warning("Plik '" + filePath + "' jest pusty. Nie ma czego wysyłać.");
                // Można by wysłać EOT od razu, ale standardowo XMODEM tego nie przewiduje
                changeState(TransferState.ERROR); // Lub ABORTED
                return;
            }
            LOGGER.info("Wczytano " + fileData.length + " bajtów z pliku.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Nie można odczytać pliku do wysłania: " + filePath, e);
            changeState(TransferState.ERROR);
            return;
        }

        // Ustaw stan oczekiwania na sygnał inicjujący od odbiornika
        changeState(TransferState.SENDER_WAIT_INIT);
        // Ustaw timeout oczekiwania na NAK lub 'C'
        resetSendInitiationTimeout();
        // Rozpocznij nasłuchiwanie odpowiedzi (przez processInternalBuffer wywoływane z ReceivedDataFromSerial)
    }

    /**
     * Resetuje (lub ustawia po raz pierwszy) timeout oczekiwania na sygnał inicjujący
     * transfer (NAK lub 'C') od odbiornika.
     * Wywoływana na początku procesu wysyłania.
     */
    private void resetSendInitiationTimeout() {
        cancelTimeoutTask();
        LOGGER.fine("Ustawiono timeout oczekiwania na sygnał NAK/'C' (" + INIT_TIMEOUT_MS + "ms)");
        // Używamy tego samego timeoutu co odbiornik na pierwszy blok
        // Dajemy odbiornikowi czas na wysłanie pierwszego NAK/C
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

     /**
      * Obsługuje sytuację timeoutu podczas wysyłania danych.
      * Wywoływana przez {@link ScheduledExecutorService}, gdy upłynie czas oczekiwania.
      * W zależności od stanu, ponawia próbę wysłania bloku/EOT lub anuluje transfer,
      * jeśli przekroczono limit prób.
      */
    private void handleSendTimeout() {
        if (currentState == TransferState.SENDER_WAIT_INIT) {
            // Timeout oczekiwania na NAK/'C'
            sendRetries++;
            LOGGER.warning("Timeout oczekiwania na NAK/'C'. Próba " + sendRetries + "/" + MAX_INIT_RETRIES);
            if (sendRetries >= MAX_INIT_RETRIES) {
                LOGGER.severe("Przekroczono limit prób oczekiwania na inicjalizację od odbiornika. Anulowanie transferu.");
                abortTransfer(false);
            } else {
                // Po prostu ustawiamy timeout ponownie, czekając dalej
                resetSendInitiationTimeout();
            }
        } else if (currentState == TransferState.WAITING_FOR_ACK) {
            // Timeout oczekiwania na ACK/NAK po wysłaniu bloku
            sendRetries++;
            LOGGER.warning("Timeout oczekiwania na ACK/NAK dla bloku " + (currentBlockIndex + 1) + ". Próba " + sendRetries + "/" + MAX_RETRIES);
            if (sendRetries >= MAX_RETRIES) {
                LOGGER.severe("Przekroczono limit prób wysłania bloku " + (currentBlockIndex + 1) + ". Anulowanie transferu.");
                abortTransfer(false);
            } else {
                // Wyślij blok ponownie
                sendNextBlock();
            }
        } else if (currentState == TransferState.WAITING_FOR_EOT_ACK) {
            // Timeout oczekiwania na ACK po wysłaniu EOT
            sendRetries++;
            LOGGER.warning("Timeout oczekiwania na ACK po EOT. Próba " + sendRetries + "/" + MAX_RETRIES);
            if (sendRetries >= MAX_RETRIES) {
                LOGGER.severe("Przekroczono limit prób wysłania EOT/oczekiwania na ACK. Anulowanie transferu.");
                abortTransfer(false);
            } else {
                // Wyślij EOT ponownie
                sendEndOfTransmission();
            }
        } else {
            LOGGER.warning("Timeout wystąpił w nieoczekiwanym stanie nadajnika: " + currentState);
        }
         // Timeouty w innych stanach są ignorowane
    }

    /**
     * Resetuje (lub ustawia) timeout oczekiwania na odpowiedź ACK lub NAK od odbiornika
     * po wysłaniu bloku danych.
     */
    private void resetAckTimeout() {
        cancelTimeoutTask();
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Resetuje (lub ustawia) timeout oczekiwania na ostateczne potwierdzenie ACK
     * od odbiornika po wysłaniu sygnału EOT.
     */
    private void resetEotAckTimeout() {
        cancelTimeoutTask();
        timeoutTaskHandler = timeoutScheduler.schedule(this::handleSendTimeout, EOT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Obsługuje pojedynczy bajt odebrany od odbiornika, gdy nadajnik jest w stanie
     * oczekiwania na odpowiedź (SENDER_WAIT_INIT, WAITING_FOR_ACK, WAITING_FOR_EOT_ACK).
     * Ta metoda jest wywoływana przez {@link #processInternalBuffer}.
     *
     * @param receivedByte Bajt odebrany od odbiornika.
     */
    private synchronized void handleSenderReceivedByte(byte receivedByte) {
        // Usunięcie bajtu z bufora jest konieczne tylko jeśli go przetwarzamy
        switch (currentState) {
            case SENDER_WAIT_INIT:
                // Oczekujemy na NAK (dla sumy kontrolnej) lub 'C' (dla CRC)
                byte expectedInitiator = useCRC ? CHAR_C : NAK;
                String expectedInitiatorName = useCRC ? "'C'" : "NAK";

                if (receivedByte == expectedInitiator) {
                    extractBytesFromBuffer(1); // Usuń bajt z bufora
                    cancelTimeoutTask(); // Otrzymaliśmy sygnał, anuluj timeout
                    LOGGER.info("[Nadajnik] Odebrano sygnał inicjujący " + expectedInitiatorName + ". Rozpoczynanie wysyłania bloku 1.");
                    sendRetries = 0; // Zresetuj licznik prób dla wysyłania bloków
                    currentBlockIndex = 0; // Upewnij się, że zaczynamy od pierwszego bloku
                    sendNextBlock(); // Wyślij pierwszy blok
                } else if (receivedByte == NAK || receivedByte == CHAR_C) {
                     extractBytesFromBuffer(1); // Usuń bajt z bufora
                     LOGGER.warning("[Nadajnik] Odebrano nieoczekiwany sygnał inicjujący (" + String.format("0x%02X", receivedByte) + "), oczekiwano " + expectedInitiatorName + ". Ignorowanie.");
                     // Można by ewentualnie dostosować tryb CRC/checksum, ale prościej jest zignorować
                     resetSendInitiationTimeout(); // Resetuj timeout i czekaj dalej na właściwy sygnał
                } else if (receivedByte == CAN) {
                     extractBytesFromBuffer(1);
                     cancelTimeoutTask();
                     LOGGER.warning("[Nadajnik] Odebrano CAN podczas oczekiwania na inicjalizację. Anulowanie transferu.");
                     abortTransfer(true);
                } else {
                     byte[] ignored = extractBytesFromBuffer(1);
                     LOGGER.finer("[Nadajnik] Ignorowanie nieoczekiwanego bajtu " + String.format("0x%02X", receivedByte) + " w stanie SENDER_WAIT_INIT.");
                }
                break;

            case WAITING_FOR_ACK:
                // Oczekujemy na ACK (potwierdzenie) lub NAK (retransmisja)
                if (receivedByte == ACK) {
                    extractBytesFromBuffer(1);
                    cancelTimeoutTask();
                    LOGGER.info("[Nadajnik] Odebrano ACK dla bloku " + (currentBlockIndex + 1) + ".");
                    sendRetries = 0; // Zresetuj licznik prób dla następnego bloku
                    currentBlockIndex++; // Przejdź do następnego bloku
                    // Sprawdź, czy są jeszcze bloki do wysłania
                    if (currentBlockIndex * BLOCK_SIZE >= fileData.length) {
                        // Wszystkie bloki wysłane, wyślij EOT
                        sendEndOfTransmission();
                    } else {
                        // Wyślij kolejny blok
                        sendNextBlock();
                    }
                } else if (receivedByte == NAK) {
                    extractBytesFromBuffer(1);
                    cancelTimeoutTask();
                    sendRetries++; // Licznik prób dla *tego samego* bloku
                    LOGGER.warning("[Nadajnik] Odebrano NAK dla bloku " + (currentBlockIndex + 1) + ". Próba retransmisji " + sendRetries + "/" + MAX_RETRIES);
                    if (sendRetries >= MAX_RETRIES) {
                        LOGGER.severe("Przekroczono limit retransmisji dla bloku " + (currentBlockIndex + 1) + ". Anulowanie transferu.");
                        abortTransfer(false);
                    } else {
                        // Wyślij ten sam blok ponownie
                        sendNextBlock();
                    }
                } else if (receivedByte == CAN) {
                    extractBytesFromBuffer(1);
                    cancelTimeoutTask();
                    LOGGER.warning("[Nadajnik] Odebrano CAN podczas oczekiwania na ACK/NAK. Anulowanie transferu.");
                    abortTransfer(true);
                } else {
                    byte[] ignored = extractBytesFromBuffer(1);
                    LOGGER.finer("[Nadajnik] Ignorowanie nieoczekiwanego bajtu " + String.format("0x%02X", receivedByte) + " w stanie WAITING_FOR_ACK.");
                }
                break;

            case WAITING_FOR_EOT_ACK:
                // Oczekujemy na ostatnie ACK po wysłaniu EOT
                if (receivedByte == ACK) {
                    extractBytesFromBuffer(1);
                    cancelTimeoutTask();
                    LOGGER.info("[Nadajnik] Odebrano końcowe ACK po EOT. Transfer zakończony pomyślnie.");
                    changeState(TransferState.COMPLETED); // Ustaw stan końcowy
                } else if (receivedByte == CAN) {
                     extractBytesFromBuffer(1);
                     cancelTimeoutTask();
                     LOGGER.warning("[Nadajnik] Odebrano CAN podczas oczekiwania na końcowe ACK. Anulowanie transferu.");
                     abortTransfer(true); // Traktuj jako anulowanie przez odbiorcę
                }
                 else {
                    // Odbiornik może np. wysłać NAK jeśli nie zrozumiał EOT, lub inne śmieci
                    byte[] ignored = extractBytesFromBuffer(1);
                    LOGGER.warning("[Nadajnik] Odebrano nieoczekiwany bajt " + String.format("0x%02X", receivedByte) + " zamiast ACK po EOT. Ignorowanie.");
                    // Można by ponowić EOT, ale standardowo czekamy na ACK lub timeout
                    // Timeout obsłuży ponowne wysłanie EOT (handleSendTimeout)
                }
                break;

             default:
                 // Nie powinniśmy tu trafić, jeśli logika w processInternalBuffer jest poprawna
                 byte[] ignored = extractBytesFromBuffer(1);
                 LOGGER.warning("handleSenderReceivedByte wywołane w nieoczekiwanym stanie: " + currentState + ". Ignorowany bajt: " + String.format("0x%02X", receivedByte));
                 break;
        }
    }

    /**
     * Przygotowuje i wysyła następny blok danych (lub ten sam w przypadku retransmisji).
     * Ustawia stan na SENDING, a po wysłaniu na WAITING_FOR_ACK i resetuje timeout ACK.
     */
    private void sendNextBlock() {
        changeState(TransferState.SENDING); // Stan przejściowy na czas przygotowania i wysłania

        int blockNumber = (currentBlockIndex + 1) % 256; // Numer bloku (1-255, zawija się)
        byte blockNumberByte = (byte) blockNumber;
        byte blockNumberComplement = (byte) ~blockNumberByte; // Dopełnienie bitowe

        // Oblicz początek i koniec danych dla bieżącego bloku
        int startIndex = currentBlockIndex * BLOCK_SIZE;
        int endIndex = Math.min(startIndex + BLOCK_SIZE, fileData.length);
        int actualDataLength = endIndex - startIndex;

        // Przygotuj payload (128 bajtów)
        byte[] payload = new byte[BLOCK_SIZE];
        if (actualDataLength > 0) {

        // Skopiuj dane pliku do payload
            System.arraycopy(fileData, startIndex, payload, 0, actualDataLength);
        }

        // Wypełnij resztę payloadu znakiem SUB (padding), jeśli dane są krótsze niż BLOCK_SIZE
        // Jeśli blok nie jest pełny, wypełnij resztę znakiem SUB (0x1A)
        if (actualDataLength < BLOCK_SIZE) {
            Arrays.fill(payload, actualDataLength, BLOCK_SIZE, SUB);
        }

        // Przygotuj cały pakiet XMODEM
        byte[] xmodemPacket;

        // Oblicz i dodaj sumę kontrolną lub CRC
        if (useCRC) {
            // Tryb CRC-16
            int crc = calculateCRC16(payload);
            byte crcHigh = (byte) (crc >> 8);
            byte crcLow = (byte) crc;
            xmodemPacket = new byte[3 + BLOCK_SIZE + 2]; // SOH, Nr, ~Nr, Dane[128], CRC[2]
            xmodemPacket[xmodemPacket.length - 2] = crcHigh;
            xmodemPacket[xmodemPacket.length - 1] = crcLow;
             LOGGER.fine("[Nadajnik] Wysyłanie bloku " + (currentBlockIndex + 1) + " (Nr: " + blockNumber + "), CRC: " + String.format("%04X", crc));
             System.out.println("[Nadajnik] Wysyłanie bloku " + (currentBlockIndex + 1) + " (Nr: " + blockNumber + "), CRC: " + String.format("%04X", crc));
        } else {
            // Tryb sumy kontrolnej
            byte checksum = calculateChecksum(payload);
            xmodemPacket = new byte[3 + BLOCK_SIZE + 1]; // SOH, Nr, ~Nr, Dane[128], Suma[1]
            xmodemPacket[xmodemPacket.length - 1] = checksum;
            LOGGER.fine("[Nadajnik] Wysyłanie bloku " + (currentBlockIndex + 1) + " (Nr: " + blockNumber + "), Suma: " + String.format("%02X", checksum));
            System.out.println("[Nadajnik] Wysyłanie bloku " + (currentBlockIndex + 1) + " (Nr: " + blockNumber + "), Suma: " + String.format("%02X", checksum));
        }

        // Uzupełnij nagłówek pakietu
        xmodemPacket[0] = SOH;
        xmodemPacket[1] = blockNumberByte;
        xmodemPacket[2] = blockNumberComplement;
        // Skopiuj payload do pakietu
        System.arraycopy(payload, 0, xmodemPacket, 3, BLOCK_SIZE);

        // Wyślij pakiet przez port szeregowy
        communicator.sendData(xmodemPacket);

        // Zmień stan na oczekiwanie na ACK/NAK i ustaw timeout
        changeState(TransferState.WAITING_FOR_ACK);
        resetAckTimeout();
    }

    /**
     * Wysyła sygnał końca transmisji (EOT).
     * Ustawia stan na SENDING_EOT, a po wysłaniu na WAITING_FOR_EOT_ACK i resetuje timeout EOT ACK.
     */
    private void sendEndOfTransmission() {
        changeState(TransferState.SENDING_EOT); // Stan przejściowy
        LOGGER.info("[Nadajnik] Wysyłanie EOT.");
        System.out.println("\n[Nadajnik] Wysyłanie EOT.");
        communicator.sendData(new byte[]{EOT});

        // Zmień stan na oczekiwanie na końcowe ACK i ustaw timeout
        sendRetries = 0; // Zresetuj licznik prób dla EOT
        changeState(TransferState.WAITING_FOR_EOT_ACK);
        resetEotAckTimeout();
    }


    // =========================================================================

    // --- Metody Pomocnicze (Wspólne / Statyczne) ---
    // =========================================================================

    /**
     * Oblicza prostą 8-bitową sumę kontrolną dla podanego bloku danych.
     * Suma jest obliczana jako suma wszystkich bajtów modulo 256.
     *
     * @param data Tablica bajtów, dla której obliczana jest suma kontrolna.
     * @return 8-bitowa suma kontrolna.
     */
    private static byte calculateChecksum(byte[] data) {
        byte checksum = 0;
        for (byte b : data) {
            checksum += b; // Sumowanie z automatycznym zawijaniem (modulo 256)
        }
        return checksum;
    }

    // Tablica predefiniowanych wartości dla CRC-16 CCITT (XMODEM)
    private static final int[] crcTable = {
            0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7,
            0x8108, 0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef,
            0x1231, 0x0210, 0x3273, 0x2252, 0x52b5, 0x4294, 0x72f7, 0x62d6,
            0x9339, 0x8318, 0xb37b, 0xa35a, 0xd3bd, 0xc39c, 0xf3ff, 0xe3de,
            0x2462, 0x3443, 0x0420, 0x1401, 0x64e6, 0x74c7, 0x44a4, 0x5485,
            0xa56a, 0xb54b, 0x8528, 0x9509, 0xe5ee, 0xf5cf, 0xc5ac, 0xd58d,
            0x3653, 0x2672, 0x1611, 0x0630, 0x76d7, 0x66f6, 0x5695, 0x46b4,
            0xb75b, 0xa77a, 0x9719, 0x8738, 0xf7df, 0xe7fe, 0xd79d, 0xc7bc,
            0x48c4, 0x58e5, 0x6886, 0x78a7, 0x0840, 0x1861, 0x2802, 0x3823,
            0xc9cc, 0xd9ed, 0xe98e, 0xf9af, 0x8948, 0x9969, 0xa90a, 0xb92b,
            0x5af5, 0x4ad4, 0x7ab7, 0x6a96, 0x1a71, 0x0a50, 0x3a33, 0x2a12,
            0xdbfd, 0xcbdc, 0xfbbf, 0xeb9e, 0x9b79, 0x8b58, 0xbb3b, 0xab1a,
            0x6ca6, 0x7c87, 0x4ce4, 0x5cc5, 0x2c22, 0x3c03, 0x0c60, 0x1c41,
            0xedae, 0xfd8f, 0xcdec, 0xddcd, 0xad2a, 0xbd0b, 0x8d68, 0x9d49,
            0x7e97, 0x6eb6, 0x5ed5, 0x4ef4, 0x3e13, 0x2e32, 0x1e51, 0x0e70,
            0xff9f, 0xefbe, 0xdfdd, 0xcffc, 0xbf1b, 0xaf3a, 0x9f59, 0x8f78,
            0x9188, 0x81a9, 0xb1ca, 0xa1eb, 0xd10c, 0xc12d, 0xf14e, 0xe16f,
            0x1080, 0x00a1, 0x30c2, 0x20e3, 0x5004, 0x4025, 0x7046, 0x6067,
            0x83b9, 0x9398, 0xa3fb, 0xb3da, 0xc33d, 0xd31c, 0xe37f, 0xf35e,
            0x02b1, 0x1290, 0x22f3, 0x32d2, 0x4235, 0x5214, 0x6277, 0x7256,
            0xb5ea, 0xa5cb, 0x95a8, 0x8589, 0xf56e, 0xe54f, 0xd52c, 0xc50d,
            0x34e2, 0x24c3, 0x14a0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
            0xa7db, 0xb7fa, 0x8799, 0x97b8, 0xe75f, 0xf77e, 0xc71d, 0xd73c,
            0x26d3, 0x36f2, 0x0691, 0x16b0, 0x6657, 0x7676, 0x4615, 0x5634,
            0xd94c, 0xc96d, 0xf90e, 0xe92f, 0x99c8, 0x89e9, 0xb98a, 0xa9ab,
            0x5844, 0x4865, 0x7806, 0x6827, 0x18c0, 0x08e1, 0x3882, 0x28a3,
            0xcb7d, 0xdb5c, 0xeb3f, 0xfb1e, 0x8bf9, 0x9bd8, 0xabbb, 0xbb9a,
            0x4a75, 0x5a54, 0x6a37, 0x7a16, 0x0af1, 0x1ad0, 0x2ab3, 0x3a92,
            0xfd2e, 0xed0f, 0xdd6c, 0xcd4d, 0xbdaa, 0xad8b, 0x9de8, 0x8dc9,
            0x7c26, 0x6c07, 0x5c64, 0x4c45, 0x3ca2, 0x2c83, 0x1ce0, 0x0cc1,
            0xef1f, 0xff3e, 0xcf5d, 0xdf7c, 0xaf9b, 0xbfba, 0x8fd9, 0x9ff8,
            0x6e17, 0x7e36, 0x4e55, 0x5e74, 0x2e93, 0x3eb2, 0x0ed1, 0x1ef0
    };

    /**
     * Oblicza 16-bitową sumę kontrolną CRC (Cyclic Redundancy Check)
     * dla podanego bloku danych, używając standardu CRC-16-CCITT
     * (często używanego w XMODEM, wielomian x^16 + x^12 + x^5 + 1).
     * Implementacja wykorzystuje tablicę przeglądową dla wydajności.
     *
     * @param data Tablica bajtów, dla której obliczane jest CRC.
     * @return 16-bitowa wartość CRC.
     */
    private static int calculateCRC16(byte[] data) {
        int crc = 0x0000; // Wartość początkowa CRC dla XMODEM
        for (byte b : data) {
            // XOR starszego bajtu CRC z nowym bajtem danych, użyj wyniku jako indeksu w tabeli
            // XOR wyniku z tabeli z młodszym bajtem CRC przesuniętym w lewo o 8 bitów
            crc = (crc << 8) ^ crcTable[((crc >> 8) ^ b) & 0xFF];
            crc &= 0xFFFF; // Upewnij się, że wynik pozostaje 16-bitowy
        }
        return crc;
    }

    /**
     * Weryfikuje, czy podany numer bloku i jego dopełnienie bitowe są zgodne.
     * W protokole XMODEM, drugi bajt po SOH powinien być dopełnieniem bitowym
     * (one's complement) pierwszego bajtu numeru bloku.
     *
     * @param blockNumberByte       Bajt numeru bloku.
     * @param blockNumberComplement Bajt dopełnienia numeru bloku.
     * @return {@code true} jeśli dopełnienie jest poprawne, {@code false} w przeciwnym razie.
     */
    private static boolean verifyBlockNumber(byte blockNumberByte, byte blockNumberComplement) {
        // Sprawdza, czy (numer + dopełnienie) daje 0xFF (czyli -1 w reprezentacji U2)
        return (byte) (blockNumberByte + blockNumberComplement) == (byte) 0xFF;
    }

    /**
     * Zmienia wewnętrzny stan transferu.
     * Loguje zmianę stanu dla celów diagnostycznych.
     *
     * @param newState Nowy stan z enum {@link TransferState}.
     */
    private void changeState(TransferState newState) {
        if (currentState != newState) {
            LOGGER.fine("Zmiana stanu: " + currentState + " -> " + newState);
             // System.out.println("Czyszczenie danych pliku z pamięci.");
            currentState = newState;
        }
    }


    /**
     * Wyłącza executor obsługujący zadania timeoutów.
     * Powinno być wywołane przy zamykaniu aplikacji,
     * aby zapobiec wyciekom wątków.
     * Ta metoda nie przerywa aktywnych timeoutów.
     */
    public void shutdownScheduler() {
        timeoutScheduler.shutdown(); // Zatrzymuje executor, pozwalając dokończyć aktywne zadania
    }
}