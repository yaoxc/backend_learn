package com.bizzan.bitrade;

import org.junit.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;

import java.io.File;


public class WalletTest {


    static String pwd = "c2ewithdraweth";
    static String path = "../www/wallet-data/ETH/keystore";

    @Test
    public  void testC() throws Exception {
        //String fileName = WalletUtils.generateNewWalletFile(pwd, new File(path), true);
        Credentials credentials = WalletUtils.loadCredentials(pwd, path + "/" + "UTC--2025-08-30T05-14-13.559000000Z--260db3aa6b460505e25702ea0129af01a6306b59.json");
        String address = credentials.getAddress();
        System.out.println(address);
    }
}
