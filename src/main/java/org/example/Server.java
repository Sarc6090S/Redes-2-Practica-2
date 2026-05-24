package org.example;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class Server {

    private static final int PUERTO_SERVIDOR = 9999;
    private static final int WINDOW_SIZE = 10;
    private static final int TIMEOUT = 500;
    private static final int TAM_DATOS = 6492;
    private static final int TAM_BUFFER = TAM_DATOS + 8 + 100;

    public static void main(String[] args) {
        Mp3Player mp3Player = new Mp3Player();

        var recurso = Server.class.getClassLoader().getResource("[Dubstep] - Droptek & Tut Tut Child - Drop That Child [Monstercat Release].mp3");
        if (recurso == null) {
            System.err.println("Canción no encontrada en resources/");
            return;
        }

        String ruta;
        try {
            ruta = new File(recurso.toURI()).getAbsolutePath();
        } catch (Exception e) {
            System.err.println("Error al resolver la ruta del archivo: " + e.getMessage());
            return;
        }

        byte[] bytesMP3 = mp3Player.leerArchivoMP3(ruta);
        if (bytesMP3 == null) {
            System.err.println("Error al leer el archivo de audio.");
            return;
        }

        byte[][] fragmentos = mp3Player.fragmentarMP3(bytesMP3, TAM_DATOS);
        int totalPaquetes = fragmentos.length;
        System.out.println("MP3 fragmentado en " + totalPaquetes + " paquetes.");

        try (DatagramSocket socket = new DatagramSocket(PUERTO_SERVIDOR)) {
            System.out.println("Servidor iniciado en puerto: " + PUERTO_SERVIDOR);
            System.out.println("Esperando mensaje READY del cliente...");

            socket.setSoTimeout(10000);
            byte[] bufferReady = new byte[10];
            DatagramPacket paqReady = new DatagramPacket(bufferReady, bufferReady.length);
            socket.receive(paqReady);

            InetAddress dirCliente = paqReady.getAddress();
            int puertoCliente = paqReady.getPort();
            System.out.println("Cliente listo en " + dirCliente + ":" + puertoCliente);

            socket.setSoTimeout(TIMEOUT);

            System.out.println("=== Iniciando transmisión Go-Back-N ===");
            System.out.println("Total de paquetes: " + totalPaquetes + " | Ventana: " + WINDOW_SIZE + " | Timeout: " + TIMEOUT + "ms");

            int base = 0;
            int nextSeqNum = 0;
            int paquetesEnviados = 0;
            int retransmisiones = 0;
            long tiempoInicio = System.currentTimeMillis();

            while (base < totalPaquetes) {
                // Llenar la ventana
                while (nextSeqNum < base + WINDOW_SIZE && nextSeqNum < totalPaquetes) {
                    Paquete p = new Paquete(nextSeqNum, totalPaquetes, fragmentos[nextSeqNum]);
                    byte[] datos = p.serializar();
                    DatagramPacket dp = new DatagramPacket(datos, datos.length, dirCliente, puertoCliente);
                    socket.send(dp);
                    paquetesEnviados++;
                    nextSeqNum++;
                }

                // Esperar ACK con timeout
                try {
                    byte[] bufferAck = new byte[TAM_BUFFER];
                    DatagramPacket paqAck = new DatagramPacket(bufferAck, bufferAck.length);
                    socket.receive(paqAck);

                    String ack = new String(paqAck.getData(), 0, paqAck.getLength()).trim();
                    if (ack.startsWith("ACK:")) {
                        int numAck = Integer.parseInt(ack.substring(4));
                        if (numAck >= base) {
                            base = numAck + 1;
                            if (base % 50 == 0 || base == totalPaquetes) {
                                double progreso = (base * 100.0) / totalPaquetes;
                                System.out.printf("Progreso: %.1f%% (%d/%d paquetes confirmados)%n",
                                        progreso, base, totalPaquetes);
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("⚠ TIMEOUT — retransmitiendo desde paquete " + base);
                    retransmisiones += (nextSeqNum - base);
                    nextSeqNum = base;
                }
            }

            long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
            double eficiencia = (totalPaquetes * 100.0) / paquetesEnviados;

            System.out.println("=== Estadísticas ===");
            System.out.printf("Tiempo total: %.2f segundos%n", tiempoTotal / 1000.0);
            System.out.println("Paquetes enviados: " + paquetesEnviados);
            System.out.println("Retransmisiones: " + retransmisiones);
            System.out.printf("Eficiencia: %.1f%%%n", eficiencia);

        } catch (SocketTimeoutException e) {
            System.err.println("Timeout esperando READY del cliente. ¿Está el cliente ejecutándose?");
        } catch (IOException e) {
            System.err.println("Error de red: " + e.getMessage());
        }
    }
}
