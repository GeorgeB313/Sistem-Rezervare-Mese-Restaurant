package com.rezervari;

public class masa {
    private int numar;
    private String nume;
    private boolean rezervata;
    private String numeClient;
    private int capacitate;
    private String zona;
    private int pozitieX;
    private int pozitieY;
    private boolean langaFereastra;
    private boolean miscareaBlocata;

    public masa(int numar) {
        this(numar, "Masa " + numar, 4, "central", 0, 0, false, false);
    }

    public masa(int numar, String nume, int capacitate, String zona, int pozitieX, int pozitieY, boolean langaFereastra, boolean miscareaBlocata) {
        this.numar = numar;
        this.nume = nume;
        this.rezervata = false;
        this.capacitate = capacitate;
        this.zona = zona;
        this.pozitieX = pozitieX;
        this.pozitieY = pozitieY;
        this.langaFereastra = langaFereastra;
        this.miscareaBlocata = miscareaBlocata;
    }

    public int getNumar() {
        return numar;
    }

    public String getNume() {
        return nume;
    }

    public boolean esteRezervata() {
        return rezervata;
    }

    public String getNumeClient() {
        return numeClient;
    }

    public int getCapacitate() {
        return capacitate;
    }

    public void setCapacitate(int capacitate) {
        this.capacitate = capacitate;
    }

    public String getZona() {
        return zona;
    }

    public int getPozitieX() {
        return pozitieX;
    }

    public int getPozitieY() {
        return pozitieY;
    }

    public void setPozitieX(int pozitieX) {
        this.pozitieX = pozitieX;
    }

    public void setPozitieY(int pozitieY) {
        this.pozitieY = pozitieY;
    }

    public boolean isLangaFereastra() {
        return langaFereastra;
    }

    public boolean isMiscareaBlocata() {
        return miscareaBlocata;
    }

    public void setLangaFereastra(boolean langaFereastra) {
        this.langaFereastra = langaFereastra;
    }

    public void setNume(String nume) {
        this.nume = nume;
    }

    public void setMiscareaBlocata(boolean miscareaBlocata) {
        this.miscareaBlocata = miscareaBlocata;
    }

    public void rezerva(String numeClient) {
        this.numeClient = numeClient;
        this.rezervata = true;
    }

    public void anuleaza() {
        this.numeClient = null;
        this.rezervata = false;
    }

    @Override
    public String toString() {
        if (rezervata)
            return nume + " (" + capacitate + ") - Rezervata de " + numeClient;
        else
            return nume + " (" + capacitate + ") - Libera" + (langaFereastra ? " | fereastra" : "") + (miscareaBlocata ? " | blocata" : "");
    }
}
