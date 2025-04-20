package org.zespol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Xmodem {

    private static final int SOH = 0x01;    // Start of Header - znak rozpoczynający pakiet danych
    private static final int EOT = 0x04;    // End of Transmission - znak oznaczający koniec transmisji
    private static final int ACK = 0x06;    // Acknowledge - potwierdzenie prawidłowego odbioru pakietu
    private static final int NAK = 0x15;    // Negative Acknowledge - żądanie ponownego przesłania pakietu (błąd)
    private static final int CAN = 0x18;    // Cancel - anulowanie transmisji
    private static final int CRC_CHAR = 0x43; // Znak 'C' - żądanie rozpoczęcia transmisji z kontrolą CRC
    private static final int SUB = 0x1A;    // Substitute - znak wypełniający dla niepełnych bloków danych

    // Stan transferu danych
    public enum TransferState {
        IDLE,           // Bezczynny, brak transferu
        EXPECTING_SOH,  // Oczekuje na pierwszy blok SOH
        RECEIVING,      // W trakcie odbierania kolejnych bajtów - SOH lub EOT
        COMPLETED,      // Transfer zakończony sukcesem
        ABORTED,        // Transfer anulowany
        ERROR           // Wystąpił błąd
    }

    private volatile TransferState currentState = TransferState.IDLE; // Początkowy stan
    private int expectedBlockNumber = 1;
    private int nakRetries = 0;
    private static final int maxNak = 6;
    private ScheduledExecutorService nakScheduler; // Wątek do wysyłania NAK
    private ScheduledFuture<?> nakTaskHandler;      // Uchwyt do zaplanowanego zadania NAK

    private final SerialCommunicator communicator;
    // Wewnętrzny bufor do przechowywania odebranych danych, jeszcze nieprzetworzonych
    private final List<Byte> buffer = new ArrayList<>();

    // Konstruktor
    public Xmodem(SerialCommunicator communicator) {
        if (communicator == null) {
            throw new IllegalArgumentException("SerialCommunicator nie może być null");
        }
        this.communicator = communicator;
    }

    public TransferState getCurrentState() {
        return currentState;
    }

    // Odbieranie danych z portu szeregowego
    public void ReceivedDataFromSerial(byte[] data) {
        // Dodaj nowe dane do wewnętrznego bufora
        for (byte b : data) {
            buffer.add(b);
        }

        processInternalBuffer();
    }

    public void startReceive() {
        // Sprawdzenie stanu
        if (currentState != TransferState.IDLE && currentState != TransferState.COMPLETED && currentState != TransferState.ABORTED && currentState != TransferState.ERROR) {
            System.err.println("XMODEM Błąd: Nie można rozpocząć nowego transferu, poprzedni jest w toku lub nie został poprawnie zakończony. Stan: " + currentState);
            return;
        }

        // Inicjalizacja stanu transferu
        buffer.clear();
        expectedBlockNumber = 1; // Zaczynamy od bloku 1
        nakRetries = 0;
        currentState = TransferState.EXPECTING_SOH;

        initiateTransferWithNak();
    }

    // Wysyłanie NAK
    private void initiateTransferWithNak() {

        cancelNakTask();

        if (currentState == TransferState.EXPECTING_SOH) {
            System.out.println("XMODEM: Wysyłanie PIERWSZEGO NAK...");
            nakScheduler = Executors.newSingleThreadScheduledExecutor();

            // Definicja zadania
            Runnable sendNakTask = () -> {
                if (currentState == TransferState.EXPECTING_SOH) {
                    if (nakRetries < maxNak) {
                        nakRetries++;
                        communicator.sendData(new byte[]{NAK});
                    } else {
                        System.out.println("XMODEM: Przekroczono limit NAK. Anulowanie.");
                        abortTransfer(false); // Anuluj
                    }
                }
                else {
                    System.out.println("XMODEM: Stan inny niż EXPECTING_SOH, zatrzymywanie wysyłania NAK.");
                    cancelNakTask();
                }
            };

            // Przypisanie zadania do scheduler'a, zrobienie uchywtu
            nakTaskHandler = nakScheduler.scheduleAtFixedRate(
                    sendNakTask,
                    0, // Początkowe opóźnienie
                    10000,      // Odstęp między wykonaniami
                    TimeUnit.MILLISECONDS // Jednostka czasu
            );

            // Zresetuj licznik NAK dla inicjalizacji
            nakRetries = 0;
        }
    }

    // Metoda do zatrzymywania cyklicznego wysyłania NAK
    private void cancelNakTask() {
        // Sprawdza, czy istnieje uchwyt do zaplanowanego zadania (nakTaskHandler) i czy zadanie nie zostało jeszcze zakończone.
        if (nakTaskHandler != null && !nakTaskHandler.isDone()) {
            System.out.println("XMODEM: Zatrzymywanie cyklicznego wysyłania NAK.");
            // Anuluje zaplanowane zadanie. Parametr false oznacza, że jeśli zadanie jest właśnie wykonywane, to dokończy bieżące wykonanie.
            nakTaskHandler.cancel(false);
        }

        //Sprawdza, czy istnieje scheduler (nakScheduler) i czy nie został jeszcze zamknięty.
        if (nakScheduler != null && !nakScheduler.isShutdown()) {
            // Zamyka scheduler - nie będzie przyjmował nowych zadań, ale dokończy już uruchomione.
            nakScheduler.shutdown();
            try {
                // Poczekaj chwilę na zakończenie zadań (opcjonalnie)
                if (!nakScheduler.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    nakScheduler.shutdownNow(); // Wymuś zatrzymanie
                }
            } catch (InterruptedException e) {
                nakScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Ustawia uchwyt na null, aby wskazać, że zadanie zostało zakończone.
        nakTaskHandler = null;
        nakScheduler = null;
    }

    // GŁÓWNA METODA PRZETWARZANIA BUFORA
    private void processInternalBuffer() {

        boolean processedSomething = true;

        while (processedSomething && !buffer.isEmpty() && (currentState == TransferState.EXPECTING_SOH || currentState == TransferState.RECEIVING))
        {
            processedSomething = false; // Resetuj flagę
            byte firstByte = buffer.getFirst();
            System.out.println("XMODEM: Przetwarzanie bufora, pierwszy bajt: 0x" + String.format("%02X", firstByte));

            if (firstByte == SOH) {
                if (buffer.size() >= 132) {
                    byte[] temp = new byte[132];
                    System.arraycopy(buffer.toArray(), 0, temp, 0, 132);
                    buffer.subList(0, 132).clear();
                    processXmodemBlock(temp);
                    processedSomething = true;
                } else {
                    break;
                }
                // TODO: NA PÓŹNIEJ
            } else if (firstByte == EOT && currentState == TransferState.RECEIVING) {
                completeTransfer();
                buffer.removeFirst();
                processedSomething = true;
                break;
            } else if (firstByte == CAN) {
                abortTransfer(true);
                if (buffer.size() >= 2) {
                    buffer.removeFirst(); // Usuń pierwszy CAN
                    buffer.removeFirst(); // Usuń drugi CAN
                } else {
                    buffer.clear(); // Wyczyść bufor, jeśli nie ma wystarczającej ilości danych
                }
                processedSomething = true;
                break;
            } else {
                // Nieznany/nieoczekiwany bajt
                System.err.println("   -> Nieoczekiwany bajt: 0x" + String.format("%02X", firstByte) + ". Odrzucanie.");
                buffer.removeFirst(); // Usuń go
                processedSomething = true; // Coś usunęliśmy
            }

        }
    }

    // METODA PRZETWARZANIA POJEDYNCZEGO BLOKU
    private void processXmodemBlock(byte[] blockData) {
        System.out.println("XMODEM: Próba przetworzenia bloku (długość: " + blockData.length + ")");
        // - Sprawdź blockNum vs expectedBlockNumber
        if (blockData[1] == expectedBlockNumber) {
            if (verifyBlockNumber(blockData[1], blockData[2])) {
                if (calculateChecksum(blockData, 2, 128) == blockData[131]) {
                    System.out.println("  -> Suma kontrolna poprawna.");
                    System.out.println("  -> Blok " + expectedBlockNumber + " ODEBRANY POPRAWNIE.");
                    byte[] payload = Arrays.copyOfRange(blockData, 3, 131);

                    // TODO: Zapisać payload do pliku

                    communicator.sendData(new byte[]{ACK});
                    nakRetries = 0;
                    if(currentState == TransferState.EXPECTING_SOH) {
                        cancelNakTask();
                    }
                    expectedBlockNumber++;
                    if (expectedBlockNumber == 256){
                        expectedBlockNumber = 0;
                    }
                    currentState = TransferState.RECEIVING;
                } else {
                    // Suma kontrolna się nie zgadza
                    System.out.println("  -> Suma kontrolna błędna.");
                    handleBlockError();
                }
            } else {
                // Dopelnienie numeru bloku się nie zgadza
                System.out.println("  -> Dopelnienie numeru bloku błędne.");
                handleBlockError();
            }
        // --- Przypadek 2: Otrzymano duplikat poprzedniego bloku ---
        }else if(blockData[1] == expectedBlockNumber - 1) {
            // Nadawca prawdopodobnie nie otrzymał naszego poprzedniego ACK
            System.out.println("  -> Otrzymano duplikat bloku " + (expectedBlockNumber - 1) + ". Wysyłam ACK.");
            communicator.sendData(new byte[]{ACK});
        } // Przypadek 3: Błąd sekwencji - otrzymano blok o zupełnie innym numerze
        else{
            System.out.println("Błedny blok danych, przerywam działanie!");
            abortTransfer(false);
        }
    }


    // Obsługa błędu bloku
    private void handleBlockError() {
        System.out.println("XMODEM: Obsługa błędu bloku...");
        nakRetries++;
        if (nakRetries > maxNak) {
            abortTransfer(false);
        } else {
            communicator.sendData(new byte[]{NAK});
        }
    }

    // METODA ZAKOŃCZENIA TRANSFERU
    private void completeTransfer() {
        System.out.println("XMODEM: Finalizowanie transferu...");
        currentState = TransferState.COMPLETED;
        cancelNakTask(); // Zatrzymaj wysyłanie NAK
    }

    // METODA ANULOWANIA TRANSFERU
    private void abortTransfer(boolean receivedCan) {
        // Sprawdź, czy już nie jest anulowany, aby uniknąć podwójnego działania
        if (currentState == TransferState.ABORTED || currentState == TransferState.ERROR) {
            return;
        }
        System.out.println("XMODEM: Anulowanie transferu. Otrzymano CAN: " + receivedCan);

        // !!! ZATRZYMAJ TIMER NAK !!!
        cancelNakTask();

        if (!receivedCan) {
            communicator.sendData(new byte[]{CAN, CAN});
        }
        currentState = TransferState.ABORTED;
    }

    private static byte calculateChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = 0; i < length; i++) {
            checksum += data[offset + i]; // Suma modulo 256 jest automatyczna dla typu byte
        }
        return checksum;
    }

    public static boolean verifyBlockNumber(byte blockNumber, byte blockNumberComplement) {
        // W XMODEM, numer_bloku + dopełnienie_numeru_bloku powinno dać 0xFF (255)
        return (byte)(blockNumber + blockNumberComplement) == (byte)0xFF;
    }


}
