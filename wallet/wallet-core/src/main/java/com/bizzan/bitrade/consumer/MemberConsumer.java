package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.ActivityRewardType;
import com.bizzan.bitrade.constant.BooleanEnum;
import com.bizzan.bitrade.constant.RewardRecordType;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.es.ESUtils;
import com.bizzan.bitrade.service.*;
import com.bizzan.bitrade.util.BigDecimalUtils;
import com.bizzan.bitrade.util.GeneratorUtil;
import com.bizzan.bitrade.util.MessageResult;
import org.apache.commons.lang.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

@Component
public class MemberConsumer {
    private Logger logger = LoggerFactory.getLogger(MemberConsumer.class);
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private CoinService coinService;
    @Autowired
    private MemberWalletService memberWalletService;
    @Autowired
    private RewardActivitySettingService rewardActivitySettingService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private RewardRecordService rewardRecordService;
    @Autowired
    private MemberTransactionService memberTransactionService;
    @Autowired
    private ESUtils esUtils;


    /**
     * 重置用户钱包地址
     *
     * @param record
     */
    @KafkaListener(topics = {"reset-member-address"})
    public void resetAddress(ConsumerRecord<String, String> record) {
        String content = record.value();
        JSONObject json = JSON.parseObject(content);
        Coin coin = coinService.findByUnit(record.key());
        Assert.notNull(coin, "coin null");
        // if(coin.getEnableRpc()==BooleanEnum.IS_TRUE){
        logger.info("rest address,unit={},memberId={},address={}",record.key(), json.getLong("uid"), json.getString("address"));
        MemberWallet memberWallet = memberWalletService.findByCoinUnitAndMemberId(record.key(), json.getLong("uid"));
        Assert.notNull(memberWallet, "wallet null");
        //此处应该再做一次鉴权，教学目的暂不增加
        memberWallet.setAddress(json.getString("address"));
        memberWalletService.save(memberWallet);
        //}
    }


    /**
     * 客户注册消息
     *
     * @param content
     */
    @KafkaListener(topics = {"member-register"})
    public void handle(String content) {
        logger.info("handle member-register,data={}", content);
        if (StringUtils.isEmpty(content)) {
            return;
        }
        JSONObject json = JSON.parseObject(content);
        if (json == null) {
            return;
        }
        //获取所有支持的币种
        List<Coin> coins = coinService.findAll();
        for (Coin coin : coins) {
            logger.info("memberId:{},unit:{}", json.getLong("uid"), coin.getUnit());
            MemberWallet wallet = new MemberWallet();
            wallet.setCoin(coin);
            wallet.setMemberId(json.getLong("uid"));
            wallet.setBalance(new BigDecimal(0));
            wallet.setFrozenBalance(new BigDecimal(0));
            wallet.setAddress("");

            /** 此处获取地址注释掉，所有币种地址由用户主动获取才生成  **/
//            if(coin.getEnableRpc() == BooleanEnum.IS_TRUE) {
//                String account = "U" + json.getLong("uid");
//                //远程RPC服务URL,后缀为币种单位
//                String serviceName = "SERVICE-RPC-" + coin.getUnit(); // 意思是 ---> 每个币 都有一个服务
//                try{
//                    String url = "http://" + serviceName + "/rpc/address/{account}";
//                    ResponseEntity<MessageResult> result = restTemplate.getForEntity(url, MessageResult.class, account);
//                    logger.info("remote call:service={},result={}", serviceName, result);
//                    if (result.getStatusCode().value() == 200) {
//                        MessageResult mr = result.getBody();
//                        logger.info("mr={}", mr);
//                        if (mr.getCode() == 0) {
//                            //返回地址成功，调用持久化
//                            String address = (String) mr.getData();
//                            wallet.setAddress(address);
//                        }
//                    }
//                }
//                catch (Exception e){
//                    logger.error("call {} failed,error={}",serviceName,e.getMessage());
//                    wallet.setAddress("");
//                }
//            }
//            else{
//                wallet.setAddress("");
//            }
            //保存
            memberWalletService.save(wallet);
        }
        //注册活动奖励
        RewardActivitySetting rewardActivitySetting = rewardActivitySettingService.findByType(ActivityRewardType.REGISTER);
        if (rewardActivitySetting != null) {
            MemberWallet memberWallet = memberWalletService.findByCoinAndMemberId(rewardActivitySetting.getCoin(), json.getLong("uid"));
            if (memberWallet == null) {
                return;
            }
            // 奖励币
            BigDecimal amount3 = JSONObject.parseObject(rewardActivitySetting.getInfo()).getBigDecimal("amount");
            memberWallet.setBalance(BigDecimalUtils.add(memberWallet.getBalance(), amount3));
            memberWalletService.save(memberWallet);
            // 保存奖励记录
            Member member = memberService.findOne(json.getLong("uid"));
            RewardRecord rewardRecord3 = new RewardRecord();
            rewardRecord3.setAmount(amount3);
            rewardRecord3.setCoin(rewardActivitySetting.getCoin());
            rewardRecord3.setMember(member);
            rewardRecord3.setRemark(rewardActivitySetting.getType().getCnName());
            rewardRecord3.setType(RewardRecordType.ACTIVITY);
            rewardRecordService.save(rewardRecord3);
            // 保存资产变更记录
            MemberTransaction memberTransaction = new MemberTransaction();
            memberTransaction.setFee(BigDecimal.ZERO);
            memberTransaction.setAmount(amount3);
            memberTransaction.setSymbol(rewardActivitySetting.getCoin().getUnit());
            memberTransaction.setType(TransactionType.ACTIVITY_AWARD);
            memberTransaction.setMemberId(member.getId());
            memberTransaction.setDiscountFee("0");
            memberTransaction.setRealFee("0");
            memberTransaction = memberTransactionService.save(memberTransaction);
        }

    }

    /**
     * 生成指定币种的钱包地址（支持ETH和ERC20 Token）
     * @param coinSymbol 币种符号（如ETH、USDT）
     * @return 钱包地址（含私钥加密存储信息，仅返回地址给调用方）
     */
//    public String generateWalletAddress(String coinSymbol) throws Exception {
//        // 1. 生成随机密钥对（私钥+公钥）：Web3j封装secp256k1算法
//        ECKeyPair ecKeyPair = Keys.createEcKeyPair();
//        BigInteger privateKey = ecKeyPair.getPrivateKey(); // 私钥（需加密存储）
//        String address = "0x" + Keys.getAddress(ecKeyPair); // 公钥→地址（自动处理哈希和截取）
//
//        logger.info("生成 {} 钱包地址：{}，私钥（加密前）：{}", coinSymbol, address, privateKey.toString(16));
//
//        // 2. 加密私钥（关键！不能明文存储，用AES加密后存入数据库）
//        String encryptedPrivateKey = encryptPrivateKey(privateKey.toString(16));
//
//        // 3. 存储地址+加密私钥到RPC服务的数据库（后续签名交易时使用）
//        WalletRecord walletRecord = WalletRecord.builder()
//                .coinSymbol(coinSymbol)
//                .address(address)
//                .encryptedPrivateKey(encryptedPrivateKey)
//                .createTime(new Date())
//                .status(1) // 地址状态：可用
//                .build();
//        walletRecordRepository.save(walletRecord);
//
//        // 4. 仅返回地址给调用方（钱包服务），私钥不对外暴露
//        return address;
//    }
//
//    /**
//     * AES加密私钥（防止私钥泄露，密钥从配置文件读取，需定期轮换）
//     * @param privateKey 明文私钥（16进制字符串）
//     * @return 加密后的私钥
//     */
//    private String encryptPrivateKey(String privateKey) throws Exception {
//        // 从配置文件读取AES密钥和偏移量（生产环境需配置在配置中心，如Nacos/Apollo）
//        @Value("${wallet.private-key.aes.key}")
//        String aesKey; // 16位（AES-128）或32位（AES-256）
//        @Value("${wallet.private-key.aes.iv}")
//        String aesIv; // 16位
//
//        // 使用AES/CBC/PKCS5Padding模式加密
//        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
//        SecretKeySpec keySpec = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
//        IvParameterSpec ivSpec = new IvParameterSpec(aesIv.getBytes(StandardCharsets.UTF_8));
//        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
//
//        byte[] encryptedBytes = cipher.doFinal(privateKey.getBytes(StandardCharsets.UTF_8));
//        return Base64.getEncoder().encodeToString(encryptedBytes); // Base64编码后存储
//    }
}
