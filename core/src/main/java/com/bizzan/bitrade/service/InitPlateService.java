package com.bizzan.bitrade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bizzan.bitrade.dao.InitPlateDao;
import com.bizzan.bitrade.entity.InitPlate;
import com.bizzan.bitrade.pagination.Criteria;
import com.bizzan.bitrade.service.Base.BaseService;

@Service
public class InitPlateService extends BaseService {

    @Autowired
    private InitPlateDao initPlateDao ;
    public InitPlate findInitPlateBySymbol(String symbol) {
        return initPlateDao.findInitPlateBySymbol(symbol);
    }

    public InitPlate save(InitPlate initPlate){
        return initPlateDao.save(initPlate);
    }

    public InitPlate saveAndFlush(InitPlate initPlate) {
       return initPlateDao.saveAndFlush(initPlate);
    }
    /** 升级说明：2.x 中 delete 需传实体时，先 findById 再 delete(entity)。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id){
        InitPlate e = initPlateDao.findById(id).orElse(null);
        if (e != null) initPlateDao.delete(e);
    }

    public Page<InitPlate> findAllByPage(Criteria<InitPlate> specification, PageRequest pageRequest) {
        return initPlateDao.findAll(specification,pageRequest);
    }
    public InitPlate findByInitPlateId(long id){
        return initPlateDao.findById(id).orElse(null);
    }
}
