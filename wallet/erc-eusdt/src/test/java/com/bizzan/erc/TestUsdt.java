package com.bizzan.erc;

import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestUsdt {

    public static void main(String[] args){
        String input = "0xa9059cbb0000000000000000000000006cc8dcbca746a6e4fdefb98e1d0df903b107fd210000000000000000000000000000000000000000000000000000000804c8b200";
        System.out.println(input);
        String addressHex = input.substring(34, 74);
        System.out.println(addressHex);
        String toAddress = "0x" + addressHex.toLowerCase();

        String amountHex = input.substring(74);
        BigInteger amountValue = new BigInteger(amountHex, 16);
        String amount = amountValue.toString();

        System.out.println(toAddress+"___"+amount);
    }


    public static List<Type> decodeInputData(String inputData,
                                             String methodName,
                                             List<TypeReference<?>> outputParameters) {
        Function function = new Function(methodName,
                Collections.<Type>emptyList(),
                outputParameters
        );
        List<Type> result = FunctionReturnDecoder.decode(
                inputData.substring(10),
                function.getOutputParameters());
        return result;
    }
}
