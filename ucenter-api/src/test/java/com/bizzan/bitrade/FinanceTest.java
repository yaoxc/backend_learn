package com.bizzan.bitrade;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FinanceTest  {

    @Test
    public void testBigdecimal(){
        BigDecimal a  = new BigDecimal("12.5");
        System.out.println(a);
        a.subtract(BigDecimal.ONE);
        System.out.println(a);
    }



}
