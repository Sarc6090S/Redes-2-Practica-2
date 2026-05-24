package org.example;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Client {

    private static final int PUERTO_CLIENTE = 5666;
    private static final int PUERTO_SERVIDOR = 9999;
    private static final int TAM_BUFFER = 7000;

    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(PUERTO_CLIENTE)) {
            System.out.println("Cliente iniciado en puerto: " + PUERTO_CLIENTE);

            InetAddress dirServidor = InetAddress.getByName("localhost");
            byte[] msgReady = "READY".getBytes();
            DatagramPacket paqReady = new DatagramPacket(msgReady, msgReady.length, dirServidor, PUERTO_SERVIDOR);
            socket.send(paqReady);
            System.out.println("READY enviado al servidor");

            System.out.println("=== Iniciando recepción Go-Back-N ===");

            byte[][] paquetesRecibidos = null;
            int totalPaquetes = -1;
            int expectedSeqNum = 0;
            int paquetesContados = 0;
            int duplicados = 0;
            long tiempoInicio = System.currentTimeMillis();

            while (true) {
                byte[] buffer = new byte[TAM_BUFFER];
                DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
                socket.receive(dp);

                Paquete p = Paquete.deserializar(dp.getData(), dp.getLength());

                if (totalPaquetes == -1) {
                    totalPaquetes = p.total;
                    paquetesRecibidos = new byte[totalPaquetes][];
                    System.out.println("Total de paquetes a recibir: " + totalPaquetes);
                }

                InetAddress dirServidor2 = dp.getAddress();
                int puertoServidor2 = dp.getPort();

                if (p.seqNum == expectedSeqNum) {
                    paquetesRecibidos[p.seqNum] = p.datos;
                    expectedSeqNum++;
                    paquetesContados++;

                    String ack = "ACK:" + p.seqNum;
                    byte[] bytesAck = ack.getBytes();
                    socket.send(new DatagramPacket(bytesAck, bytesAck.length, dirServidor2, puertoServidor2));

                    if (paquetesContados % 50 == 0) {
                        double progreso = (paquetesContados * 100.0) / totalPaquetes;
                        System.out.printf("Recibido: %.1f%% (%d/%d)%n", progreso, paquetesContados, totalPaquetes);
                    }

                    if (expectedSeqNum == totalPaquetes) {
                        break;
                    }

                } else if (p.seqNum < expectedSeqNum) {
                    duplicados++;
                    String ack = "ACK:" + (expectedSeqNum - 1);
                    byte[] bytesAck = ack.getBytes();
                    socket.send(new DatagramPacket(bytesAck, bytesAck.length, dirServidor2, puertoServidor2));

                } else {
                    // Fuera de orden — descartar (Go-Back-N estricto)
                    System.out.println("⚠ Paquete fuera de orden: " + p.seqNum + " (esperado: " + expectedSeqNum + ")");
                    if (expectedSeqNum > 0) {
                        String ack = "ACK:" + (expectedSeqNum - 1);
                        byte[] bytesAck = ack.getBytes();
                        socket.send(new DatagramPacket(bytesAck, bytesAck.length, dirServidor2, puertoServidor2));
                    }
                }
            }

            // Reconstruir archivo
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] fragmento : paquetesRecibidos) {
                baos.write(fragmento);
            }
            byte[] archivoReconstruido = baos.toByteArray();

            String rutaSalida = "cancion_recibida.mp3";

            try (FileOutputStream fos = new FileOutputStream(rutaSalida)) {
                fos.write(archivoReconstruido);
            }

            long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
            double tamanioMB = archivoReconstruido.length / (1024.0 * 1024.0);

            System.out.printf("✓ Archivo reconstruido: %s (%.2f MB)%n", rutaSalida, tamanioMB);
            System.out.printf("Tiempo total: %.2f segundos%n", tiempoTotal / 1000.0);
            System.out.println("Paquetes recibidos: " + paquetesContados + " | Duplicados: " + duplicados);

//            System.out.println("Abriendo con el reproductor del sistema...");
//            Desktop.getDesktop().open(new File(rutaSalida));
            ReproductorMP3.abrir(rutaSalida);


        } catch (IOException e) {
            System.err.println("Error de red: " + e.getMessage());
        }
    }
}
