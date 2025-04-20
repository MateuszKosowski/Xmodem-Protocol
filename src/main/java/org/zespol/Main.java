package org.zespol;
import java.util.Scanner;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        SerialCommunicator sc = new SerialCommunicator();
        Xmodem xmodem = new Xmodem(sc);
        sc.setXmodem(xmodem);
        xmodem.setOutputFileName("./output.txt");

        sc.listPorts();
        String port = "COM10";
        sc.open(port);


        System.out.println("Chcesz odbierać czy wysłać dane na port COM10?");
        System.out.println("1. Odbieranie");
        System.out.println("2. Wysyłanie");
        System.out.println("3. Wyjście");
        String choice = scanner.nextLine();

        switch(choice){
            case "1":


                sc.startListening();
                xmodem.startReceive();

                System.out.println("Odbieranie danych...");

                boolean transferInProgress = true;
                while(transferInProgress) {
                    Xmodem.TransferState currentState = xmodem.getCurrentState();
                    switch (currentState) {
                        case COMPLETED:
                            System.out.println("\nMain: Transfer zakończony pomyślnie.");
                            transferInProgress = false;
                            break;
                        case ABORTED:
                            System.out.println("\nMain: Transfer został anulowany.");
                            transferInProgress = false;
                            break;
                        case ERROR:
                            System.out.println("\nMain: Wystąpił błąd krytyczny podczas transferu.");
                            transferInProgress = false;
                            break;
                        case IDLE:
                            if (xmodem.getCurrentState() != Xmodem.TransferState.EXPECTING_SOH) {
                                System.out.println("\nMain: Stan wrócił do IDLE, zakładam koniec.");
                                transferInProgress = false;
                            }
                            break;
                        case EXPECTING_SOH:
                        case RECEIVING:
                            System.out.print(".");
                            break;
                    }

                    if (!transferInProgress) {
                        break;
                    }

                    try {
                        Thread.sleep(1000); // Sprawdzaj stan co sekundę
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("\nMain: Wątek główny przerwany.");
                        transferInProgress = false; // Zakończ pętlę
                    }
                }

                System.out.println("\nMonitorowanie zakończone.");

                sc.stopListening();
                sc.closePort();
                scanner.close();
                System.out.println("Aplikacja zakończona.");
                break;


            case "2":
                // Chwilowo pomijamy
                break;

            case "3":
                sc.closePort();
                scanner.close();
                System.out.println("Aplikacja zakończona.");
                break;

            default:
                System.out.println("Zły wybór, podaj 1,2 lub 3!");
                sc.closePort();
                scanner.close();
                System.out.println("Aplikacja zakończona.");
                break;
        }

    }
}
