package com.bizzan.bc.wallet.controller;

import com.alibaba.fastjson.JSONObject;
import com.bizzan.bc.wallet.component.EthWatcher;
import com.bizzan.bc.wallet.entity.Account;
import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.service.AccountService;
import com.bizzan.bc.wallet.service.EthService;
import com.bizzan.bc.wallet.util.MessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.utils.Convert;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;


// TODO: v1版本在Account中记录账户余额，不与wallet数据打通
// 后面通过kafka管理充值提现，实现用户web3钱包与数据库memberWallet数据联通

@RestController
@RequestMapping("/rpc")
public class WalletController {
    private Logger logger = LoggerFactory.getLogger(WalletController.class);
    @Autowired
    private EthService service;
    @Autowired
    private Web3j web3j;
    @Autowired
    private EthWatcher watcher;
    @Autowired
    private Coin coin;
    @Autowired
    private AccountService accountService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @GetMapping("height")
    public MessageResult getHeight() {
        try {
            EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
            long rpcBlockNumber = blockNumber.getBlockNumber().longValue();
            MessageResult result = new MessageResult(0, "success");
            result.setData(rpcBlockNumber);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "查询失败,error:" + e.getMessage());
        }
    }


    @GetMapping("address/{account}")
    public MessageResult getNewAddress(@PathVariable String account, @RequestParam(required = false, defaultValue = "") String password) {
        logger.info("create new account={},password={}", account, password);
        try {

            String address;
            Account acct = accountService.findByName("ETH", account);
            if (acct != null) {
                address = acct.getAddress();
                accountService.save(acct);
            } else {
                address = service.createNewWallet(account, password);
            }
            JSONObject json = new JSONObject();
            json.put("uid", account);
            json.put("address", address);
            kafkaTemplate.send("reset-member-address", "ETH", json.toJSONString());
            logger.info("reset-member-address deposit,coin={} req={}", coin.getUnit(), json);

            MessageResult result = new MessageResult(0, "success");
            result.setData(address);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "rpc error:" + e.getMessage());
        }
    }

    @GetMapping("miner-fee")
    public MessageResult getMinerFee() {
        try {
            MessageResult result = new MessageResult(0, "success");
            result.setData(service.getMinerFee(coin.getGasLimit()).toString());
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "rpc error:" + e.getMessage());
        }
    }

    //转账到某个地址-在这里可以做归集使用
    @GetMapping("transfer")
    public MessageResult transfer(String address, BigDecimal amount, BigDecimal fee) {
        logger.info("transfer:address={},amount={},fee={}", address, amount, fee);
        try {
            if (fee == null || fee.compareTo(BigDecimal.ZERO) <= 0) {
                fee = service.getMinerFee(coin.getGasLimit());
            }
            MessageResult result = service.transferFromWallet(address, amount, fee, coin.getMinCollectAmount());
            logger.info("返回结果 : " + result.toString());
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "error:" + e.getMessage());
        }
    }

    //从提币地址提币
    @GetMapping("withdraw")
    public MessageResult withdraw(String address, BigDecimal amount, String account,
                                  @RequestParam(name = "sync", required = false, defaultValue = "true") Boolean sync,
                                  @RequestParam(name = "withdrawId", required = false, defaultValue = "") String withdrawId) {
        logger.info("withdraw:to={},amount={},sync={},withdrawId={}", address, amount, sync,withdrawId);
        try {
            MessageResult result = service.transferFromWithdrawWallet(address, amount,sync,withdrawId);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "error:" + e.getMessage());
        }
    }

    /**
     * 获取热钱包总额
     *
     * @return
     */
    @GetMapping("balance")
    public MessageResult balance() {
        try {
            BigDecimal balance = accountService.findBalanceSum();
            MessageResult result = new MessageResult(0, "success");
            result.setData(balance);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "查询失败，error:" + e.getMessage());
        }
    }


    /**
     * 获取单个地址余额（用户可见余额）
     *
     * @param address
     * @return
     */
    @GetMapping("balance/{account}")
    public MessageResult addressBalance(@PathVariable String account) {
        try {
            Account ac = accountService.findByName(coin.getUnit(), account);
            BigDecimal memberWalletBalance = ac.getMemberWalletBalance();
            MessageResult result = new MessageResult(0, "success");
            result.setData(memberWalletBalance);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "查询失败，error:" + e.getMessage());
        }
    }

    @GetMapping("transaction/{txid}")
    public MessageResult transaction(@PathVariable String txid) throws IOException {
        EthTransaction transaction = web3j.ethGetTransactionByHash(txid).send();
        EthGasPrice gasPrice = web3j.ethGasPrice().send();

        System.out.println(gasPrice.getGasPrice());
        System.out.println(transaction.getRawResponse());
        return MessageResult.success("");
    }

    @GetMapping("gas-price")
    public MessageResult gasPrice() throws IOException {
        try {
            BigInteger gasPrice = service.getGasPrice();
            MessageResult result = new MessageResult(0, "success");
            result.setData(Convert.fromWei(gasPrice.toString(), Convert.Unit.GWEI));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return MessageResult.error(500, "查询失败，error:" + e.getMessage());
        }
    }

    @GetMapping("sync-block")
    public MessageResult manualSync(Long startBlock, Long endBlock) {
        try {
            watcher.replayBlockInit(startBlock, endBlock);
        } catch (IOException e) {
            e.printStackTrace();
            return MessageResult.error(500, "同步失败：" + e.getMessage());
        }
        return MessageResult.success();
    }
}
