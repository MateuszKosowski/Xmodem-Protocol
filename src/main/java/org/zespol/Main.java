package org.zespol;

import java.util.Scanner;
import java.io.File;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        SerialCommunicator sc = new SerialCommunicator();
        Xmodem xmodem = new Xmodem(sc);
        sc.setXmodem(xmodem); // Przekaż instancję Xmodem do SerialCommunicator

        String selectedPort = selectPort(scanner, sc);
        if (selectedPort == null) {
            System.out.println("Nie wybrano portu. Zamykanie aplikacji.");
            scanner.close();
            return;
        }

        sc.open(selectedPort);
        if (sc.isPortOpen()) { // Dodajmy metodę sprawdzającą czy port jest otwarty w SerialCommunicator
            System.out.println("Pomyślnie otwarto port " + selectedPort);
        } else {
            System.out.println("Nie udało się otworzyć portu " + selectedPort + ". Zamykanie aplikacji.");
            scanner.close();
            xmodem.shutdownScheduler(); // Zamknij scheduler Xmodem przed wyjściem
            return;
        }


        // Wybór trybu pracy
        System.out.println("\nWybierz tryb pracy:");
        System.out.println("1. Odbiornik (Odbierz plik)");
        System.out.println("2. Nadajnik (Wyślij plik)");
        System.out.println("3. Wyjście");
        System.out.print("Wybór: ");
        String modeChoice = scanner.nextLine();

        boolean useCRC = false;
        if (modeChoice.equals("1") || modeChoice.equals("2")) {
            // Wybór metody sumy kontrolnej
            System.out.println("Wybierz metodę kontroli błędów:");
            System.out.println("1. Suma kontrolna (standardowa)");
            System.out.println("2. CRC-16 (zalecane)");
            System.out.print("Wybór: ");
            String checksumChoice = scanner.nextLine();
            if (checksumChoice.equals("2")) {
                useCRC = true;
                System.out.println("Wybrano CRC-16.");
            } else {
                System.out.println("Wybrano sumę kontrolną.");
            }
        }

        switch (modeChoice) {
            case "1": // Odbiornik
                System.out.print("Podaj nazwę pliku, do którego zapisać odebrane dane (np. C:\\odbior\\plik.txt): ");
                String outputFilePath = scanner.nextLine();
                if (outputFilePath.isEmpty()) {
                    outputFilePath = "odebrany_plik.dat"; // Domyślna nazwa
                    System.out.println("Nie podano nazwy, używam domyślnej: " + outputFilePath);
                }
                xmodem.setOutputFileName(outputFilePath);

                sc.startListening(); // Zacznij nasłuchiwać na porcie
                xmodem.startReceive(useCRC); // Rozpocznij procedurę odbioru (wyśle NAK lub C)

                System.out.println("Rozpoczęto proces odbierania. Oczekiwanie na dane...");
                monitorTransfer(xmodem); // Monitoruj stan transferu

                sc.stopListening(); // Zakończ nasłuchiwanie
                break;

            case "2": // Nadajnik
                System.out.print("Podaj pełną ścieżkę do pliku, który chcesz wysłać (np. C:\\pliki\\plik.txt): ");
                String inputFilePath = scanner.nextLine();

                // Sprawdź czy plik istnieje
                File inputFile = new File(inputFilePath);
                if (!inputFile.exists() || !inputFile.isFile()) {
                    System.out.println("Błąd: Plik '" + inputFilePath + "' nie istnieje lub nie jest plikiem.");
                } else {
                    sc.startListening(); // Nadajnik też musi nasłuchiwać (na NAK/C, ACK/NAK, CAN)
                    xmodem.startSend(inputFilePath, useCRC); // Rozpocznij procedurę wysyłania

                    System.out.println("Rozpoczęto proces wysyłania. Oczekiwanie na sygnał od odbiornika...");
                    monitorTransfer(xmodem); // Monitoruj stan transferu

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

        // Zamykanie zasobów
        sc.closePort();
        xmodem.shutdownScheduler(); // Zatrzymaj wątek timeoutów Xmodem
        scanner.close();
        System.out.println("Aplikacja zakończona.");
    }

    // Metoda do wyboru portu szeregowego
    private static String selectPort(Scanner scanner, SerialCommunicator sc) {
        String[] portNames = sc.listPorts(); // Zmieniona metoda listPorts - zwraca tablicę nazw
        if (portNames == null || portNames.length == 0) {
            System.out.println("Nie znaleziono dostępnych portów szeregowych.");
            return null;
        }

        System.out.println("\nDostępne porty szeregowe:");
        for (int i = 0; i < portNames.length; i++) {
            System.out.println((i + 1) + ": " + portNames[i]);
        }

        int choice = -1;
        while (choice < 1 || choice > portNames.length) {
            System.out.print("Wybierz numer portu (1-" + portNames.length + "): ");
            try {
                String line = scanner.nextLine();
                choice = Integer.parseInt(line);
                if (choice < 1 || choice > portNames.length) {
                    System.out.println("Nieprawidłowy numer portu.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Proszę podać numer.");
            }
        }
        return portNames[choice - 1];
    }

    // Metoda do monitorowania stanu transferu Xmodem
    private static void monitorTransfer(Xmodem xmodem) {
        boolean transferInProgress = true;
        Xmodem.TransferState lastReportedState = null;

        while (transferInProgress) {
            Xmodem.TransferState currentState = xmodem.getCurrentState();

            // Raportuj zmianę stanu tylko raz
            if (currentState != lastReportedState) {
                System.out.println("\n[Monitor] Stan transferu: " + currentState);
                lastReportedState = currentState;
            } else {
                // Opcjonalnie: drukuj kropkę co sekundę w niektórych stanach
                if (currentState == Xmodem.TransferState.RECEIVING ||
                        currentState == Xmodem.TransferState.SENDING ||
                        currentState == Xmodem.TransferState.WAITING_FOR_ACK ||
                        currentState == Xmodem.TransferState.EXPECTING_SOH) {
                    System.out.print(".");
                }
            }


            switch (currentState) {
                case COMPLETED:
                    System.out.println("\n[Monitor] Transfer zakończony pomyślnie.");
                    transferInProgress = false;
                    break;
                case ABORTED:
                    System.out.println("\n[Monitor] Transfer został anulowany.");
                    transferInProgress = false;
                    break;
                case ERROR:
                    System.out.println("\n[Monitor] Wystąpił błąd krytyczny podczas transferu.");
                    transferInProgress = false;
                    break;
                case IDLE:
                    System.out.println("\n[Monitor] Stan niespodziewanie wrócił do IDLE. Prawdopodobnie wystąpił błąd.");
                    break;
                // Stany przejściowe - kontynuuj pętlę
                case EXPECTING_SOH:
                case RECEIVING:
                case SENDING:
                case WAITING_FOR_ACK:
                case SENDING_EOT:
                case WAITING_FOR_EOT_ACK:
                    // Pętla będzie kontynuowana
                    break;
            }

            if (!transferInProgress) {
                break; // Wyjdź z pętli while
            }

            // Poczekaj chwilę przed kolejnym sprawdzeniem stanu
            try {
                Thread.sleep(1000); // Sprawdzaj stan co sekundę
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("\n[Monitor] Wątek monitorujący przerwany.");
                xmodem.abortTransfer(false); // Spróbuj anulować transfer
                transferInProgress = false;
            }
        }
        System.out.println("[Monitor] Zakończono monitorowanie transferu.");
    }
}