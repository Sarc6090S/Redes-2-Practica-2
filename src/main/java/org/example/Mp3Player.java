package org.example;

import java.io.FileInputStream;
import java.io.IOException;

public class Mp3Player{

    public byte[] leerArchivoMP3(String archivo) {
        try (FileInputStream fis = new FileInputStream(archivo)) {
            return fis.readAllBytes();
        } catch (IOException e) {
            System.err.println("Error al abrir archivo: " + archivo);
            return null;
        }
    }

    public byte [][] fragmentarMP3 (byte [] datos, int tamPaquete){
        try{
            if (datos == null || tamPaquete <=0){return null;}

            int fragTotales = (int) Math.ceil((double) datos.length / tamPaquete);
            byte [][] datosFrag = new byte[fragTotales][];
            for (int i = 0; i < fragTotales; i++){
                int inicio = i * tamPaquete;
                int fin = Math.min(inicio + tamPaquete, datos.length);
                int longitud = fin - inicio;
                datosFrag[i] = new byte[longitud];
                System.arraycopy(datos, inicio, datosFrag[i], 0, longitud);            }
            return datosFrag;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
