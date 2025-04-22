package org.zespol;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Zarządza komunikacją przez port szeregowy, w tym otwieraniem, zamykaniem portu,
 * wysyłaniem i odbieraniem danych. Implementuje {@link SerialPortDataListener}
 * do asynchronicznego odbierania danych.
 */
public class SerialCommunicator implements SerialPortDataListener {

    private final HexFormat hexFormat = HexFormat.of().withUpperCase(); // Formatter do logowania danych w formacie heksadecymalnym.
    private Xmodem xmodem = null; // Handler protokołu Xmodem do przetwarzania danych.
    private SerialPort chosenPort = null; // Aktualnie wybrany i otwarty port szeregowy.
    private InputStream inputStream = null; // Strumień wejściowy do odczytu danych z portu.
    private OutputStream outputStream = null; // Strumień wyjściowy do zapisu danych do portu.

    /**
     * Ustawia obiekt obsługujący protokół Xmodem, który będzie przetwarzał odebrane dane.
     *
     * @param handler Obiekt implementujący logikę Xmodem.
     */
    public void setXmodem(Xmodem handler) {
        this.xmodem = handler;
    }

    /**
     * Zwraca listę nazw systemowych dostępnych portów szeregowych.
     *
     * @return Tablica stringów zawierająca nazwy dostępnych portów lub pusta tablica, jeśli żaden port nie jest dostępny.
     */
    public String[] listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            return new String[0]; // Zwróć pustą tablicę, jeśli nie ma portów.
        }
        String[] portNames = new String[ports.length];
        for (int i = 0; i < ports.length; ++i) {
            portNames[i] = ports[i].getSystemPortName();
        }
        return portNames;
    }

    /**
     * Otwiera i konfiguruje port szeregowy o podanej nazwie.
     * Ustawia parametry komunikacji na 9600 baud, 8 bitów danych, 1 bit stopu, brak parzystości (8N1).
     * Inicjalizuje strumienie wejściowy i wyjściowy.
     *
     * @param portName Nazwa systemowa portu do otwarcia (np. "COM3" lub "/dev/ttyS0").
     */
    public void open(String portName) {
        chosenPort = SerialPort.getCommPort(portName);

        if (chosenPort.openPort()) {
            System.out.println("Port " + chosenPort.getSystemPortName() + " otwarty.");
            // Konfiguracja parametrów portu: 9600 baud, 8 bitów danych, 1 bit stopu, brak parzystości.
            chosenPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);

            // Ustawienie timeoutów. Przy użyciu listenera, timeout odczytu ma mniejsze znaczenie,
            // ale jest ustawiony na 100ms. Zapis jest nieblokujący (timeout 0ms).
            chosenPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

            // Pobranie strumieni do komunikacji.
            inputStream = chosenPort.getInputStream();
            outputStream = chosenPort.getOutputStream();

            System.out.println("Port skonfigurowany: 9600, 8N1. Strumienie gotowe.");
        } else {
            System.err.println("Nie udało się otworzyć portu " + chosenPort.getSystemPortName());
            chosenPort = null; // Ustawienie na null, jeśli otwarcie się nie powiodło.
        }
    }

    /**
     * Sprawdza, czy port szeregowy jest aktualnie otwarty.
     *
     * @return true, jeśli port jest otwarty, false w przeciwnym razie.
     */
    public boolean isPortOpen() {
        return chosenPort != null && chosenPort.isOpen();
    }

    /**
     * Zamyka aktualnie otwarty port szeregowy.
     * Najpierw zatrzymuje nasłuchiwanie, a następnie zamyka port i zwalnia zasoby (strumienie).
     */
    public void closePort() {
        if (isPortOpen()) {
            stopListening(); // Ważne, aby zatrzymać listener przed zamknięciem portu.
            if (chosenPort.closePort()) {
                System.out.println("Port " + chosenPort.getSystemPortName() + " zamknięty.");
            } else {
                System.err.println("Nie udało się zamknąć portu " + chosenPort.getSystemPortName());
            }
        }
        // Zwolnienie zasobów niezależnie od powodzenia zamknięcia.
        chosenPort = null;
        inputStream = null;
        outputStream = null;
    }

    /**
     * Wysyła tablicę bajtów przez otwarty port szeregowy.
     * Loguje wysłane dane w formacie heksadecymalnym.
     *
     * @param message Tablica bajtów do wysłania.
     */
    public void sendData(byte[] message) {
        if (outputStream != null) {
            try {
                outputStream.write(message);
                outputStream.flush(); // Upewnij się, że dane zostały wysłane.
                System.out.println("--> Wysłano " + message.length + " bajtów: [" + hexFormat.formatHex(message) + "]");
            } catch (IOException e) {
                System.err.println("Błąd podczas wysyłania danych: " + e.getMessage());
                // Rozważ powiadomienie handlera Xmodem o błędzie.
                // if (xmodem != null) xmodem.notifyError("Send Error: " + e.getMessage());
            }
        } else {
            System.err.println("Błąd: Port nie jest otwarty lub strumień wyjściowy jest niedostępny. Nie można wysłać danych.");
        }
    }

    /**
     * Rozpoczyna nasłuchiwanie na zdarzenia danych przychodzących na porcie szeregowym.
     * Rejestruje ten obiekt jako listener zdarzeń.
     */
    public void startListening() {
        if (isPortOpen()) {
            chosenPort.removeDataListener(); // Upewnij się, że nie ma starych listenerów.
            chosenPort.addDataListener(this); // Dodaj bieżący obiekt jako listener.
            System.out.println("Rozpoczęto nasłuchiwanie na porcie " + chosenPort.getSystemPortName());
        } else {
            System.err.println("Port nie jest otwarty, nie można rozpocząć nasłuchiwania.");
        }
    }

    /**
     * Zatrzymuje nasłuchiwanie na zdarzenia danych na porcie szeregowym.
     * Usuwa ten obiekt z listy listenerów portu.
     */
    public void stopListening() {
        // Sprawdź tylko, czy obiekt portu istnieje, nawet jeśli jest zamknięty (listener mógł pozostać).
        if (chosenPort != null) {
            chosenPort.removeDataListener();
            System.out.println("Zakończono nasłuchiwanie na porcie " + (chosenPort.isOpen() ? chosenPort.getSystemPortName() : "(port zamknięty)"));
        }
    }


    /**
     * Określa typy zdarzeń, na które ten listener ma reagować.
     * W tym przypadku nasłuchujemy tylko na zdarzenie dostępności danych.
     *
     * @return Maska bitowa określająca typy zdarzeń, na które listener ma reagować.
     */
    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    /**
     * Metoda wywoływana, gdy na porcie szeregowym pojawi się zdarzenie, na które listener nasłuchuje.
     * Odczytuje dostępne dane ze strumienia wejściowego i przekazuje je do handlera Xmodem.
     *
     * @param event Obiekt zdarzenia portu szeregowego zawierający informacje o zdarzeniu.
     */
    @Override
    public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
            return; // Ignoruj inne typy zdarzeń.
        }

        if (inputStream == null) {
            System.err.println("Błąd: Zdarzenie danych, ale strumień wejściowy jest null.");
            return;
        }

        try {
            int bytesAvailable = inputStream.available(); // Sprawdź ile bajtów jest dostępnych.
            if (bytesAvailable > 0) {
                byte[] readBuffer = new byte[bytesAvailable];
                int numRead = inputStream.read(readBuffer); // Odczytaj dane do bufora.

                if (numRead > 0) {
                    byte[] receivedData = Arrays.copyOf(readBuffer, numRead); // Skopiuj tylko odczytane bajty.
                    System.out.println("<-- Odebrano " + numRead + " bajtów: [" + hexFormat.formatHex(receivedData) + "]");

                    // Przekaż odebrane dane do handlera Xmodem, jeśli jest dostępny.
                    if (xmodem != null) {
                        xmodem.ReceivedDataFromSerial(receivedData);
                    } else {
                        System.out.println("Odebrano dane, ale brak obiektu Xmodem do ich przetworzenia.");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Błąd odczytu danych z portu szeregowego: " + e.getMessage());
            // Rozważ anulowanie transferu Xmodem w przypadku błędu.
            if (xmodem != null) {
                xmodem.abortTransfer(false); // Anuluj transfer, ale nie z powodu błędu użytkownika.
            }
        }
    }
}