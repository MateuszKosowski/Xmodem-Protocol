package org.zespol;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HexFormat; // Użyj standardowego HexFormat

public class SerialCommunicator implements SerialPortDataListener {

    private final HexFormat hexFormat = HexFormat.of().withUpperCase(); // Do logowania danych hex
    private Xmodem xmodem = null; // Obiekt, który przetwarza dane
    private SerialPort chosenPort = null;
    private InputStream inputStream = null;
    private OutputStream outputStream = null;

    public void setXmodem(Xmodem handler) {
        this.xmodem = handler;
    }

    // Zwraca listę nazw dostępnych portów
    public String[] listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            return new String[0]; // Zwróć pustą tablicę, jeśli nie ma portów
        }
        String[] portNames = new String[ports.length];
        for (int i = 0; i < ports.length; ++i) {
            portNames[i] = ports[i].getSystemPortName();
            // Opcjonalnie można dodać opis: portNames[i] = ports[i].getSystemPortName() + " (" + ports[i].getDescriptivePortName() + ")";
        }
        return portNames;
    }

    public void open(String portName) {
        chosenPort = SerialPort.getCommPort(portName);

        if (chosenPort.openPort()) {
            System.out.println("Port " + chosenPort.getSystemPortName() + " otwarty.");
            // Konfiguracja parametrów portu
            // 9600 baud, 8 bitów danych, 1 bit stopu, brak parzystości
            chosenPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);

            // Ustawienie timeoutów - ważne dla blokującego odczytu, ale my używamy listenera
            // TIMEOUT_READ_BLOCKING może nie być idealny z listenerem, ale ustawmy coś
            // 100ms timeout odczytu, 0ms zapisu (zapis nie blokuje)
            chosenPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

            // Pobierz strumienie
            inputStream = chosenPort.getInputStream();
            outputStream = chosenPort.getOutputStream();

            System.out.println("Port skonfigurowany: 9600, 8N1. Strumienie gotowe.");
        } else {
            System.err.println("Nie udało się otworzyć portu " + chosenPort.getSystemPortName());
            chosenPort = null;
        }
    }

    // Sprawdza, czy port jest otwarty
    public boolean isPortOpen() {
        return chosenPort != null && chosenPort.isOpen();
    }

    public void closePort() {
        if (isPortOpen()) {
            stopListening(); // Najpierw zatrzymaj listenera
            if (chosenPort.closePort()) {
                System.out.println("Port " + chosenPort.getSystemPortName() + " zamknięty.");
            } else {
                System.err.println("Nie udało się zamknąć portu " + chosenPort.getSystemPortName());
            }
        }
        chosenPort = null;
        inputStream = null;
        outputStream = null;
    }

    public void sendData(byte[] message) {
        if (outputStream != null) {
            try {
                outputStream.write(message);
                outputStream.flush(); // Wymuś wysłanie danych
                System.out.println("--> Wysłano " + message.length + " bajtów: [" + hexFormat.formatHex(message) + "]");
            } catch (IOException e) {
                System.err.println("Błąd podczas wysyłania danych: " + e.getMessage());
                // Można by tu zgłosić błąd do Xmodem lub Main
            }
        } else {
            System.err.println("Błąd: Port nie jest otwarty lub strumień wyjściowy jest niedostępny. Nie można wysłać danych.");
        }
    }

    public void startListening() {
        if (isPortOpen()) {
            // Upewnij się, że poprzedni listener jest usunięty
            chosenPort.removeDataListener();
            // Dodaj listenera zdarzeń
            chosenPort.addDataListener(this);
            System.out.println("Rozpoczęto nasłuchiwanie na porcie " + chosenPort.getSystemPortName());
        } else {
            System.err.println("Port nie jest otwarty, nie można rozpocząć nasłuchiwania.");
        }
    }

    public void stopListening() {
        if (chosenPort != null) { // Sprawdź tylko czy obiekt istnieje, niekoniecznie czy otwarty
            chosenPort.removeDataListener();
            System.out.println("Zakończono nasłuchiwanie na porcie " + (chosenPort.isOpen() ? chosenPort.getSystemPortName() : "(port zamknięty)"));
        }
    }


    @Override
    public int getListeningEvents() {
        // Nasłuchuj tylko na zdarzenie dostępności danych
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
            return; // Ignoruj inne typy zdarzeń
        }

        if (inputStream == null) {
            System.err.println("Błąd: Zdarzenie danych, ale strumień wejściowy jest null.");
            return;
        }

        try {
            // Sprawdź ile bajtów jest dostępnych bez blokowania
            int bytesAvailable = inputStream.available();
            if (bytesAvailable > 0) {
                byte[] readData = new byte[bytesAvailable];
                int numRead = inputStream.read(readData); // Odczytaj dostępne bajty

                if (numRead > 0) {
                    System.out.println("<-- Odebrano " + numRead + " bajtów: [" + hexFormat.formatHex(Arrays.copyOf(readData, numRead)) + "]");
                    // Przekaż dane do Xmodem, jeśli jest ustawiony
                    if (xmodem != null) {
                        xmodem.ReceivedDataFromSerial(Arrays.copyOf(readData, numRead)); // Przekaż kopię z faktyczną liczbą odczytanych bajtów
                    } else {
                        System.out.println("Odebrano dane, ale brak obiektu Xmodem do ich przetworzenia.");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Błąd odczytu danych z portu szeregowego: " + e.getMessage());
            // W przypadku błędu IO można by rozważyć zamknięcie portu lub powiadomienie Xmodem
            if (xmodem != null) {
                xmodem.abortTransfer(false); // Anuluj transfer w przypadku błędu portu
            }
        }
    }
}