package com.bizzan.bitrade.test.service;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bizzan.bitrade.entity.Member;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.test.BaseTest;


public class MemberServiceTest extends BaseTest {

	@Autowired
	private MemberService memberService;
	
	@Test
	public void test() {
        // 升级说明：BaseService/TopBaseService 未指定泛型时 findById 擦除为 Object，需强转
        Member member = (Member) memberService.findById(25L);
        System.out.println(">>>>>>>>>>>>>>"+member);
        
	}

}
