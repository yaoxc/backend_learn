package com.bizzan.bitrade.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bizzan.bitrade.dao.base.BaseDao;
import com.bizzan.bitrade.entity.MemberInviteStastic;

@Repository
public interface MemberInviteStasticDao extends  BaseDao<MemberInviteStastic> {

	MemberInviteStastic findByMemberId(Long memberId);
	
	/** 升级说明：原 findById(Long) 与 CrudRepository.findById(ID) 返回 Optional 冲突，故改名为 findOneById，避免与 2.x API 冲突。 */
	MemberInviteStastic findOneById(Long id);
	
	@Query(value = "select * from member_invite_stastic order by estimated_reward desc limit :count", nativeQuery = true)
	List<MemberInviteStastic> getTopTotalAmount(@Param("count") int count);

	@Query(value = "select * from member_invite_stastic order by level_one desc limit :count", nativeQuery = true)
	List<MemberInviteStastic> getTopInviteCount(@Param("count") int count);
}
