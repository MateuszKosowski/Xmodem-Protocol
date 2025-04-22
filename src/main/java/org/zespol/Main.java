package org.zespol;

import java.util.Scanner;
import java.io.File;

/**
 * Główna klasa aplikacji konsolowej do transferu plików za pomocą protokołu XMODEM
 * przez port szeregowy. Umożliwia użytkownikowi wybór portu, trybu pracy (nadajnik/odbiornik)
 * oraz metody sprawdzania błędów (suma kontrolna/CRC-16).
 */
public class Main {

    /**
     * Punkt wejścia aplikacji. Inicjalizuje komunikację szeregową i protokół Xmodem,
     * obsługuje interakcję z użytkownikiem w celu konfiguracji transferu,
     * uruchamia i monitoruje proces transferu, a na końcu zamyka zasoby.
     *
     * @param args Argumenty linii poleceń (nieużywane).
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        SerialCommunicator sc = new SerialCommunicator();
        Xmodem xmodem = new Xmodem(sc);
        // Ustanowienie dwukierunkowej zależności: SerialCommunicator potrzebuje Xmodem do przekazywania danych,
        // a Xmodem potrzebuje SerialCommunicator do wysyłania danych.
        sc.setXmodem(xmodem); // Przekaż instancję Xmodem do SerialCommunicator

        // Krok 1: Wybór portu szeregowego
        String selectedPort = selectPort(scanner, sc);
        if (selectedPort == null) {
            System.out.println("Nie wybrano portu. Zamykanie aplikacji.");
            scanner.close();
            return;
        }

        // Krok 2: Otwarcie i konfiguracja wybranego portu
        sc.open(selectedPort);
        if (sc.isPortOpen()) {
            System.out.println("Pomyślnie otwarto port " + selectedPort);
        } else {
            // Jeśli portu nie udało się otworzyć, zakończ aplikację
            System.out.println("Nie udało się otworzyć portu " + selectedPort + ". Zamykanie aplikacji.");
            scanner.close();
            xmodem.shutdownScheduler(); // Zamknij scheduler Xmodem, jeśli został uruchomiony (choć tu raczej nie zdążył)
            return;
        }

        // Krok 3: Wybór trybu pracy (Odbiornik / Nadajnik / Wyjście)
        System.out.println("\nWybierz tryb pracy:");
        System.out.println("1. Odbiornik (Odbierz plik)");
        System.out.println("2. Nadajnik (Wyślij plik)");
        System.out.println("3. Wyjście");
        System.out.print("Wybór: ");
        String modeChoice = scanner.nextLine();

        boolean useCRC = false; // Domyślnie używaj sumy kontrolnej
        // Jeśli wybrano tryb transferu, zapytaj o metodę kontroli błędów
        if (modeChoice.equals("1") || modeChoice.equals("2")) {
            System.out.println("Wybierz metodę kontroli błędów:");
            System.out.println("1. Suma kontrolna (standardowa)");
            System.out.println("2. CRC-16 (zalecane)");
            System.out.print("Wybór: ");
            String checksumChoice = scanner.nextLine();
            if (checksumChoice.equals("2")) {
                useCRC = true; // Ustaw flagę na użycie CRC-16
                System.out.println("Wybrano CRC-16.");
            } else {
                System.out.println("Wybrano sumę kontrolną.");
            }
        }

        // Krok 4: Uruchomienie wybranego trybu
        switch (modeChoice) {
            case "1": // Odbiornik
                System.out.print("Podaj nazwę pliku, do którego zapisać odebrane dane (np. C:\\odbior\\plik.txt): ");
                String outputFilePath = scanner.nextLine();
                // Użyj domyślnej nazwy pliku, jeśli użytkownik nic nie wpisze
                if (outputFilePath.isEmpty()) {
                    outputFilePath = "odebrany_plik.dat";
                    System.out.println("Nie podano nazwy, używam domyślnej: " + outputFilePath);
                }
                xmodem.setOutputFileName(outputFilePath); // Ustaw nazwę pliku wyjściowego w Xmodem

                // Rozpocznij nasłuchiwanie na porcie przed inicjalizacją odbioru
                sc.startListening();
                // Rozpocznij procedurę odbioru - Xmodem wyśle NAK lub 'C'
                xmodem.startReceive(useCRC);

                System.out.println("Rozpoczęto proces odbierania. Oczekiwanie na dane...");
                // Monitoruj postęp transferu w oddzielnej metodzie
                monitorTransfer(xmodem);

                sc.stopListening(); // Zakończ nasłuchiwanie po zakończeniu transferu
                break;

            case "2": // Nadajnik
                System.out.print("Podaj pełną ścieżkę do pliku, który chcesz wysłać (np. C:\\pliki\\plik.txt): ");
                String inputFilePath = scanner.nextLine();

                // Sprawdź, czy podany plik istnieje i jest plikiem
                File inputFile = new File(inputFilePath);
                if (!inputFile.exists() || !inputFile.isFile()) {
                    System.out.println("Błąd: Plik '" + inputFilePath + "' nie istnieje lub nie jest plikiem.");
                } else {
                    // Nadajnik również musi nasłuchiwać (na NAK/C, ACK/NAK, CAN)
                    sc.startListening();
                    // Rozpocznij procedurę wysyłania - Xmodem czeka na NAK/C
                    xmodem.startSend(inputFilePath, useCRC);

                    System.out.println("Rozpoczęto proces wysyłania. Oczekiwanie na sygnał od odbiornika...");
                    // Monitoruj postęp transferu
                    monitorTransfer(xmodem);

                    sc.stopListening(); // Zakończ nasłuchiwanie
                }
                break;

            case "3": // Wyjście
                System.out.println("Wybrano wyjście.");
                break;

            default:
                System.out.println("Nieprawidłowy wybór trybu.");
                break;
        }

        // Krok 5: Zamykanie zasobów przed zakończeniem aplikacji
        System.out.println("\nZamykanie zasobów...");
        sc.closePort(); // Zamknij port szeregowy
        xmodem.shutdownScheduler(); // Zatrzymaj wątek timeoutów Xmodem
        scanner.close(); // Zamknij Scanner
        System.out.println("Aplikacja zakończona.");
    }

    /**
     * Wyświetla listę dostępnych portów szeregowych i prosi użytkownika o wybór jednego z nich.
     *
     * @param scanner Obiekt {@link Scanner} do odczytu danych wejściowych od użytkownika.
     * @param sc      Obiekt {@link SerialCommunicator} do pobrania listy dostępnych portów.
     * @return Nazwa systemowa wybranego portu (np. "COM3") lub {@code null}, jeśli nie znaleziono portów lub użytkownik nie dokonał wyboru.
     */
    private static String selectPort(Scanner scanner, SerialCommunicator sc) {
        // Pobierz listę dostępnych portów
        String[] portNames = sc.listPorts();
        if (portNames == null || portNames.length == 0) {
            System.out.println("Nie znaleziono dostępnych portów szeregowych.");
            return null; // Zwróć null, jeśli brak portów
        }

        // Wyświetl dostępne porty
        System.out.println("\nDostępne porty szeregowe:");
        for (int i = 0; i < portNames.length; i++) {
            System.out.println((i + 1) + ": " + portNames[i]);
        }

        // Pętla prosząca o wybór aż do uzyskania poprawnego numeru
        int choice = -1;
        while (choice < 1 || choice > portNames.length) {
            System.out.print("Wybierz numer portu (1-" + portNames.length + "): ");
            try {
                String line = scanner.nextLine(); // Odczytaj całą linię
                choice = Integer.parseInt(line); // Spróbuj sparsować do liczby całkowitej
                if (choice < 1 || choice > portNames.length) {
                    System.out.println("Nieprawidłowy numer portu. Spróbuj ponownie.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Nieprawidłowe wejście. Proszę podać numer.");
            }
        }
        // Zwróć nazwę portu odpowiadającą wyborowi użytkownika (indeksowanie od 0)
        return portNames[choice - 1];
    }

    /**
     * Monitoruje stan transferu XMODEM w pętli, wyświetlając zmiany stanu
     * aż do zakończenia transferu (sukces, błąd, anulowanie).
     *
     * @param xmodem Obiekt {@link Xmodem}, którego stan ma być monitorowany.
     */
    private static void monitorTransfer(Xmodem xmodem) {
        boolean transferInProgress = true;
        Xmodem.TransferState lastReportedState = null; // Przechowuje ostatnio zaraportowany stan

        System.out.println("[Monitor] Rozpoczęto monitorowanie transferu...");

        // Pętla działa dopóki transfer jest w toku
        while (transferInProgress) {
            Xmodem.TransferState currentState = xmodem.getCurrentState(); // Pobierz aktualny stan

            // Raportuj zmianę stanu tylko raz, aby uniknąć powtórzeń
            if (currentState != lastReportedState) {
                System.out.println("\n[Monitor] Stan transferu: " + currentState);
                lastReportedState = currentState; // Zaktualizuj ostatnio zaraportowany stan
            } else {
                // Opcjonalne wizualne wskazanie postępu (np. kropka co sekundę)
                // w stanach oczekiwania lub transferu danych.
                if (currentState == Xmodem.TransferState.RECEIVING ||
                        currentState == Xmodem.TransferState.SENDING ||
                        currentState == Xmodem.TransferState.WAITING_FOR_ACK ||
                        currentState == Xmodem.TransferState.EXPECTING_SOH) {
                    System.out.print("."); // Drukuj kropkę jako wskaźnik aktywności
                }
            }

            // Sprawdź, czy transfer się zakończył
            switch (currentState) {
                case COMPLETED:
                    System.out.println("\n[Monitor] Transfer zakończony pomyślnie.");
                    transferInProgress = false; // Zakończ pętlę monitorowania
                    break;
                case ABORTED:
                    System.out.println("\n[Monitor] Transfer został anulowany.");
                    transferInProgress = false; // Zakończ pętlę monitorowania
                    break;
                case ERROR:
                    System.out.println("\n[Monitor] Wystąpił błąd krytyczny podczas transferu.");
                    transferInProgress = false; // Zakończ pętlę monitorowania
                    break;
                case IDLE:
                case RECEIVER_INIT:
                case SENDER_WAIT_INIT:
                case EXPECTING_SOH:
                case RECEIVING:
                case SENDING:
                case WAITING_FOR_ACK:
                case SENDING_EOT:
                case WAITING_FOR_EOT_ACK:
                    // Nic nie rób, pętla będzie kontynuowana
                    break;
            }

            if (!transferInProgress) {
                break; // Wyjdź z pętli while natychmiast po wykryciu końca transferu
            }

            // Poczekaj chwilę przed kolejnym sprawdzeniem stanu, aby nie obciążać CPU
            try {
                Thread.sleep(500); // Sprawdzaj stan co pół sekundy (zamiast 1s, dla szybszej reakcji)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Przywróć flagę przerwania
                System.out.println("\n[Monitor] Wątek monitorujący przerwany.");
                // Spróbuj bezpiecznie anulować transfer, jeśli wątek został przerwany
                xmodem.abortTransfer(false); // false oznacza, że przerwanie nie jest inicjowane przez CAN
                transferInProgress = false; // Zakończ pętlę
            }
        }
        System.out.println("[Monitor] Zakończono monitorowanie transferu.");
    }
}