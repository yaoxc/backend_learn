package com.bizzan.bc.wallet.service;

import com.bizzan.bc.wallet.entity.Account;
import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.entity.Contract;
import com.bizzan.bc.wallet.util.EthConvert;
import com.bizzan.bc.wallet.util.MessageResult;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Convert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;


@Component
public class EthService {
    private Logger logger = LoggerFactory.getLogger(EthService.class);
    @Autowired
    private Coin coin;
    @Autowired
    private Web3j web3j;
    @Autowired
    private PaymentHandler paymentHandler;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JsonRpcHttpClient jsonrpcClient;

    /**
     * @Data 只是 Lombok 帮你生成 getter/setter/hashCode/equals/toString，并没有“制造” Spring Bean；
     * 你能 @Autowired 成功，是因为 别处（你自己或 Spring Boot）把该类注册成了 Bean，和 @Data 本身毫无关系。
     * ——很多人把“两个注解干的事”混为一谈了
     *
     * (required = false) 是 Spring 的“软注入”开关：
     * 容器里找得到 bean 就注入，找不到也不报错，字段直接留 null（或保留原值）。
     */
    @Autowired(required = false)
    private Contract contract;

    @Autowired
    /**
     * ResourceLoader 就是 Spring 提供的“统一资源打开器”——不管你的文件在 jar 里、磁盘上、还是远程服务器，
     * 只要给 Spring 一个字符串路径，它就能帮你封装成 Resource，随后随心所欲地读、判断、转换。
     */
    private ResourceLoader resourceLoader;

    public String createNewWallet(String account, String password) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException, CipherException, IOException, CipherException {
        logger.info("====>  Generate new wallet file for ETH.");
        String fileName = WalletUtils.generateNewWalletFile(password, new File(coin.getKeystorePath()), true);
        Credentials credentials = WalletUtils.loadCredentials(password, coin.getKeystorePath() + "/" + fileName);
        String address = credentials.getAddress();
        accountService.saveOne(account, fileName, address);
        return address;
    }


    /**
     * 同步余额
     *
     * @param address
     * @throws IOException
     */
    public void syncAddressBalance(String address) throws IOException {
        BigDecimal balance = getBalance(address);
        accountService.updateBalance(address, balance);
    }

    public MessageResult transferFromWithdrawWallet(String toAddress, BigDecimal amount, boolean sync, String withdrawId) {

        String fileName = coin.getKeystorePath() + "/" + coin.getWithdrawWallet();
        // 将URL转换为File对象
        try {

            // 将URL转换为File对象
            File withdrawFile = getResourceAsFile(fileName);

            MessageResult result = withdraw(withdrawFile, coin.getWithdrawWalletPassword(), toAddress, amount, sync, withdrawId);
            logger.info("withdraw result:{}", result.toString());

            return result;
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "读取本地提现ks失败");
        }
    }


    // TODO: 提现，设置了默认fee，接通账户后移除扣的手续费逻辑和account参数
    public MessageResult transferFromWithdrawWallet(String toAddress, String account, BigDecimal amount, boolean sync, String withdrawId) {
        String fileName = coin.getKeystorePath() + "/" + coin.getWithdrawWallet();
        BigDecimal fee = BigDecimal.ZERO;
        try {
            fee = getMinerFee(coin.getGasLimit());
        } catch (Exception e) {
            e.printStackTrace();
            fee = new BigDecimal(0.0002);
        }
        try {
            Account ac = accountService.findByName(coin.getUnit(), account);
            if (ac == null) {
                return new MessageResult(500, "账户不存在");
            }
            // 将URL转换为File对象
            File withdrawFile = getResourceAsFile(fileName);
            // 判断余额是否足够
            BigDecimal memberWalletBalance = ac.getMemberWalletBalance();
            // 实际到账数量， TODO：后续移除
            BigDecimal arriveAmount = amount.subtract(fee);
            if (memberWalletBalance.compareTo(amount) < 0) {
                return new MessageResult(500, "提现失败");
            }
            MessageResult result = withdraw(withdrawFile, coin.getWithdrawWalletPassword(), toAddress, arriveAmount, sync, withdrawId);
            logger.info("withdraw result:{}", result.toString());
            if (result.getCode() == 0 && result.getData() != null) {
                accountService.updateMemberWalletBalance(ac.getAddress(), memberWalletBalance.subtract(amount));
            }
            return result;
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "读取本地提现ks失败");
        }
    }

    public MessageResult withdraw(File walletFile, String password, String toAddress, BigDecimal amount, boolean sync, String withdrawId) {
        Credentials credentials;
        try {
            credentials = WalletUtils.loadCredentials(password, walletFile);
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "钱包文件不存在");
        } catch (CipherException e) {
            e.printStackTrace();
            return new MessageResult(500, "解密失败，密码不正确");
        }

        return paymentHandler.transferEth(credentials, toAddress, amount);

    }

    public MessageResult withdrawToken(File walletFile, String password, String toAddress, BigDecimal amount, boolean sync, String withdrawId) {
        Credentials credentials;
        try {
            credentials = WalletUtils.loadCredentials(password, walletFile);
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "钱包文件不存在");
        } catch (CipherException e) {
            e.printStackTrace();
            return new MessageResult(500, "解密失败，密码不正确");
        }

        return paymentHandler.transferToken(credentials, toAddress, amount);

    }


    public File getResourceAsFile(String filePath) throws IOException {
        Resource resource = resourceLoader.getResource("classpath:" + filePath);

        if (!resource.exists()) {
            throw new IOException("资源不存在: " + filePath);
        }

        // 检查资源是否在文件系统中（非JAR包）
        try {
            return resource.getFile(); // 如果资源在文件系统中，直接返回
        } catch (IOException e) {
            // 如果资源在JAR包中，提取到临时文件
            return extractFromJarToTempFile(resource, filePath);
        }
    }

    private File extractFromJarToTempFile(Resource resource, String originalPath) throws IOException {
        // 从路径中提取文件名
        String fileName = originalPath.substring(originalPath.lastIndexOf('/') + 1);

        // 创建临时文件
        File tempFile = File.createTempFile("jar-extract-", "-" + fileName);
        tempFile.deleteOnExit(); // JVM退出时删除

        // 将资源内容复制到临时文件
        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }

    public MessageResult transfer(String walletFile, String password, String toAddress, BigDecimal amount, boolean sync, String withdrawId) {
        Credentials credentials;
        try {
            credentials = WalletUtils.loadCredentials(password, walletFile);
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "钱包文件不存在");
        } catch (CipherException e) {
            e.printStackTrace();
            return new MessageResult(500, "解密失败，密码不正确");
        }
        if (sync) {
            return paymentHandler.transferEth(credentials, toAddress, amount);
        } else {
            paymentHandler.transferEthAsync(credentials, toAddress, amount, withdrawId);
            return new MessageResult(0, "提交成功");
        }
    }

    public BigDecimal getBalance(String address) throws IOException {
        EthGetBalance getBalance = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
        return Convert.fromWei(getBalance.getBalance().toString(), Convert.Unit.ETHER);
    }

    public BigInteger getGasPrice() throws IOException {
        EthGasPrice gasPrice = web3j.ethGasPrice().send();
        BigInteger baseGasPrice = gasPrice.getGasPrice();
        return new BigDecimal(baseGasPrice).multiply(coin.getGasSpeedUp()).toBigInteger();
    }

    public MessageResult rechargeToMemberWallet(String address, BigDecimal amount) {
        Account account = accountService.findByAddress(address);
        if (account == null) {
            MessageResult messageResult = new MessageResult(500, "没有找到账户");
            logger.info(messageResult.toString());
            return messageResult;
        }
        BigDecimal memberWalletBalance = account.getMemberWalletBalance();
        try {
            accountService.updateMemberWalletBalance(account.getAddress(), memberWalletBalance.add(amount));
        } catch (Exception e) {
            e.printStackTrace();
        }
        MessageResult result = new MessageResult(0, "success");
        return result;
    }

    public MessageResult withdrawFromMemberWallet(String address, BigDecimal amount) {
        Account account = accountService.findByAddress(address);
        if (account == null) {
            MessageResult messageResult = new MessageResult(500, "没有找到账户");
            logger.info(messageResult.toString());
            return messageResult;
        }
        BigDecimal memberWalletBalance = account.getMemberWalletBalance();
        try {
            accountService.updateMemberWalletBalance(account.getAddress(), memberWalletBalance.subtract(amount));
        } catch (Exception e) {
            e.printStackTrace();
        }
        MessageResult result = new MessageResult(0, "success");
        return result;
    }

    public MessageResult transferFromWallet(String address, BigDecimal amount, BigDecimal fee, BigDecimal minAmount) {
        logger.info("transferFromWallet 方法");
        List<Account> accounts = accountService.findByBalance(minAmount);
        if (accounts == null || accounts.size() == 0) {
            MessageResult messageResult = new MessageResult(500, "没有满足条件的转账账户(大于0.1)!");
            logger.info(messageResult.toString());
            return messageResult;
        }
        BigDecimal transferredAmount = BigDecimal.ZERO;
        for (Account account : accounts) {
            BigDecimal realAmount = account.getBalance().subtract(fee);
            if (realAmount.compareTo(amount.subtract(transferredAmount)) > 0) {
                realAmount = amount.subtract(transferredAmount);
            }
            logger.info("begin accumulate from " + account);
            MessageResult result = transfer(coin.getKeystorePath() + "/" + account.getWalletFile(), "", address, realAmount, true, "");
            if (result.getCode() == 0 && result.getData() != null) {
                logger.info("transfer address={},amount={},txid={}", account.getAddress(), realAmount, result.getData());
                transferredAmount = transferredAmount.add(realAmount);
                try {
                    syncAddressBalance(account.getAddress());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (transferredAmount.compareTo(amount) >= 0) {
                break;
            }
        }
        MessageResult result = new MessageResult(0, "success");
        result.setData(transferredAmount);
        return result;
    }

    public MessageResult transferToken(String fromAddress, String toAddress, BigDecimal amount, boolean sync) {


        Account account = accountService.findByAddress(fromAddress);
        Credentials credentials;
        try {
            credentials = WalletUtils.loadCredentials("", coin.getKeystorePath() + "/" + account.getWalletFile());
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "私钥文件不存在");
        } catch (CipherException e) {
            e.printStackTrace();
            return new MessageResult(500, "解密失败，密码不正确");
        }

        return paymentHandler.transferToken(credentials, toAddress, amount);
    }

    public MessageResult transferTokenFromWithdrawWallet(String toAddress, BigDecimal amount, boolean sync, String withdrawId) {
        String fileName = coin.getKeystorePath() + "/" + coin.getWithdrawWallet();
        try {

            // 将URL转换为File对象
            File withdrawFile = getResourceAsFile(fileName);
            MessageResult result = withdrawToken(withdrawFile, coin.getWithdrawWalletPassword(), toAddress, amount, sync, withdrawId);
            logger.info("withdraw result:{}", result.toString());
            return result;
        } catch (IOException e) {
            e.printStackTrace();
            return new MessageResult(500, "读取本地提现ks失败");
        }
    }


    public BigDecimal getTokenBalance(String address) throws IOException {
        BigInteger balance = BigInteger.ZERO;
        Function fn = new Function("balanceOf", Arrays.asList(new org.web3j.abi.datatypes.Address(address)), Collections.<TypeReference<?>>emptyList());
        String data = FunctionEncoder.encode(fn);
        Map<String, String> map = new HashMap<String, String>();
        map.put("to", contract.getAddress());
        map.put("data", data);
        try {
            String methodName = "eth_call";
            Object[] params = new Object[]{map, "latest"};
            String result = jsonrpcClient.invoke(methodName, params, Object.class).toString();
            logger.info(String.format("query token balance address=%s,balance=%s", address, result));
            if (StringUtils.isNotEmpty(result)) {
                if ("0x".equalsIgnoreCase(result) || result.length() == 2) {
                    result = "0x0";
                }
                balance = new BigInteger(result.substring(2), 16);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            logger.info("查询接口ERROR");
        }
        return EthConvert.fromWei(new BigDecimal(balance), contract.getUnit());
    }

    public BigDecimal getMinerFee(BigInteger gasLimit) throws IOException {
        BigDecimal fee = new BigDecimal(getGasPrice().multiply(gasLimit));
        return Convert.fromWei(fee, Convert.Unit.ETHER);
    }

    public Boolean isTransactionSuccess(String txid) throws IOException {
        EthTransaction transaction = web3j.ethGetTransactionByHash(txid).send();
        try {
            if (transaction != null && transaction.getTransaction().get() != null) {
                Transaction tx = transaction.getTransaction().get();
                if (!tx.getBlockHash().equalsIgnoreCase("0x0000000000000000000000000000000000000000000000000000000000000000")) {
                    EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(txid).send();
                    if (receipt != null && receipt.getTransactionReceipt().get().getStatus().equalsIgnoreCase("0x1")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return false;
    }
}
