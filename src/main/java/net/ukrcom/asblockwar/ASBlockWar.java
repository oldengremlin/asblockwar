/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package net.ukrcom.asblockwar;

/**
 *
 * @author olden
 */
public class ASBlockWar {

    public static void main(String[] args) {
        java.awt.EventQueue.invokeLater(() -> {
            mainFrame mf = new mainFrame();
            mf.setArgs(args);
            mf.setVisible(true);
        });
    }
}
