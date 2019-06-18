package stest.tron.wallet.dailybuild.zentoken;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.DecryptNotes;
import org.tron.api.GrpcAPI.DiversifierMessage;
import org.tron.api.GrpcAPI.EmptyMessage;
import org.tron.api.GrpcAPI.ExpandedSpendingKeyMessage;
import org.tron.api.GrpcAPI.IncomingViewingKeyMessage;
import org.tron.api.GrpcAPI.Note;
import org.tron.api.GrpcAPI.ViewingKeyMessage;
import org.tron.api.WalletGrpc;
import org.tron.api.WalletSolidityGrpc;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Utils;
import org.tron.core.Wallet;
import org.tron.core.config.args.Args;
import stest.tron.wallet.common.client.Configuration;
import stest.tron.wallet.common.client.Parameter.CommonConstant;
import stest.tron.wallet.common.client.utils.PublicMethed;
import stest.tron.wallet.common.client.utils.ShieldAddressInfo;

@Slf4j
public class WalletTestZenToken007 {

  private final String testKey002 = Configuration.getByPath("testng.conf")
      .getString("foundationAccount.key1");
  private final byte[] fromAddress = PublicMethed.getFinalAddress(testKey002);
  Optional<ShieldAddressInfo> sendShieldAddressInfo;
  Optional<ShieldAddressInfo> receiverShieldAddressInfo;
  String sendShieldAddress;
  String receiverShieldAddress;
  List<Note> shieldOutList = new ArrayList<>();
  DecryptNotes notes;
  String memo;
  Note sendNote;
  Note receiverNote;
  private static ByteString assetAccountId = null;
  BytesMessage ak;
  BytesMessage nk;

  private ManagedChannel channelFull = null;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  private ManagedChannel channelSolidity = null;
  private WalletSolidityGrpc.WalletSolidityBlockingStub blockingStubSolidity = null;
  private ManagedChannel channelSolidity1 = null;
  private WalletSolidityGrpc.WalletSolidityBlockingStub blockingStubSolidity1 = null;
  private String fullnode = Configuration.getByPath("testng.conf").getStringList("fullnode.ip.list")
      .get(0);
  private String soliditynode = Configuration.getByPath("testng.conf")
      .getStringList("solidityNode.ip.list").get(0);
  private String soliditynode1 = Configuration.getByPath("testng.conf")
      .getStringList("solidityNode.ip.list").get(1);
  private String foundationZenTokenKey = Configuration.getByPath("testng.conf")
      .getString("defaultParameter.zenTokenOwnerKey");
  byte[] foundationZenTokenAddress = PublicMethed.getFinalAddress(foundationZenTokenKey);
  private String zenTokenId = Configuration.getByPath("testng.conf")
      .getString("defaultParameter.zenTokenId");
  private byte[] tokenId = zenTokenId.getBytes();
  private Long zenTokenFee = Configuration.getByPath("testng.conf")
      .getLong("defaultParameter.zenTokenFee");
  private Long costTokenAmount = 10 * zenTokenFee;
  private Long sendTokenAmount = 8 * zenTokenFee;

  ECKey ecKey1 = new ECKey(Utils.getRandom());
  byte[] zenTokenOwnerAddress = ecKey1.getAddress();
  String zenTokenOwnerKey = ByteArray.toHexString(ecKey1.getPrivKeyBytes());

  /**
   * constructor.
   */
  @BeforeSuite
  public void beforeSuite() {
    Wallet wallet = new Wallet();
    Wallet.setAddressPreFixByte(CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
  }

  /**
   * constructor.
   */
  @BeforeClass(enabled = true)
  public void beforeClass() {
    PublicMethed.printAddress(foundationZenTokenKey);
    PublicMethed.printAddress(zenTokenOwnerKey);
    channelFull = ManagedChannelBuilder.forTarget(fullnode).usePlaintext(true)
        .build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);

    channelSolidity = ManagedChannelBuilder.forTarget(soliditynode)
        .usePlaintext(true)
        .build();
    blockingStubSolidity = WalletSolidityGrpc.newBlockingStub(channelSolidity);

    channelSolidity1 = ManagedChannelBuilder.forTarget(soliditynode1)
        .usePlaintext(true)
        .build();
    blockingStubSolidity1 = WalletSolidityGrpc.newBlockingStub(channelSolidity1);

    Assert.assertTrue(PublicMethed.transferAsset(zenTokenOwnerAddress, tokenId,
        costTokenAmount, foundationZenTokenAddress, foundationZenTokenKey, blockingStubFull));
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    Args.getInstance().setFullNodeAllowShieldedTransaction(true);
    sendShieldAddressInfo = PublicMethed.generateShieldAddress();
    sendShieldAddress = sendShieldAddressInfo.get().getAddress();
    logger.info("sendShieldAddressInfo:" + sendShieldAddressInfo);
    memo = "Shield memo in" + System.currentTimeMillis();
    shieldOutList = PublicMethed.addShieldOutputList(shieldOutList,sendShieldAddress,
        "" + (sendTokenAmount - zenTokenFee),memo);
    Assert.assertTrue(PublicMethed.sendShieldCoin(zenTokenOwnerAddress,sendTokenAmount,null,
        null,shieldOutList,null,0,zenTokenOwnerKey,blockingStubFull));
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    notes = PublicMethed.listShieldNote(sendShieldAddressInfo,blockingStubFull);
    sendNote = notes.getNoteTxs(0).getNote();

  }

  /**
   * constructor.
   */
  @Test(enabled = true, description = "Get spending key")
  public void test1GetSpendingKeyAndgetExpandedSpendingKey() {
    logger.info("------------------");
    String spendingKey = ByteArray.toHexString(blockingStubFull
        .getSpendingKey(EmptyMessage.newBuilder().build()).toByteArray());
    logger.info(spendingKey);
    BytesMessage sk = BytesMessage.newBuilder()
        .setValue(ByteString.copyFrom(ByteArray.fromHexString(spendingKey))).build();
    ExpandedSpendingKeyMessage esk = blockingStubFull.getExpandedSpendingKey(sk);
    logger.info("esk:{}", ByteArray.toHexString(esk.toByteArray()));
    logger.info("ask:{}", ByteArray.toHexString(esk.getAsk().toByteArray()));
    logger.info("nsk:{}", ByteArray.toHexString(esk.getNsk().toByteArray()));
    logger.info("ovk:{}", ByteArray.toHexString(esk.getOvk().toByteArray()));
  }

  /**
   * constructor.
   */
  @Test(enabled = true, description = "Get diversifier")
  public void test2GetDiversifier() {
    DiversifierMessage diversifierMessage = blockingStubFull
        .getDiversifier(EmptyMessage.newBuilder().build());
    logger.info(ByteArray.toHexString(diversifierMessage.getD().toByteArray()));
  }

  /**
   * constructor.
   */
  @Test(enabled = true, description = "Get Ak From Ask")
  public void test3GetAkFromAsk() {
    String ask = System.currentTimeMillis() + "RandomAsk";
    BytesMessage ask1 = BytesMessage.newBuilder()
        .setValue(ByteString.copyFrom(ByteArray.fromHexString(ask))).build();
    ak = blockingStubFull.getAkFromAsk(ask1);
    logger.info("ak:{}", ByteArray.toHexString(ak.toByteArray()));
  }

  /**
   * constructor.
   */
  @Test(enabled = true, description = "Get Nk From Nsk")
  public void test4GetNkFromNsk() {
    String nsk = System.currentTimeMillis() + "RandomNsk";
    BytesMessage nsk1 = BytesMessage.newBuilder()
        .setValue(ByteString.copyFrom(ByteArray.fromHexString(nsk))).build();
    nk = blockingStubFull.getNkFromNsk(nsk1);
    logger.info("nk:{}", ByteArray.toHexString(nk.toByteArray()));
  }

  /**
   * constructor.
   */
  @Test(enabled = true, description = "Get incoming viewing key")
  public void test5GetIncomingViewingKey() {
    ViewingKeyMessage vk = ViewingKeyMessage.newBuilder()
        .setAk(ByteString.copyFrom(ByteArray
            .fromHexString(ByteArray.toHexString(ak.toByteArray()))))
        .setNk(ByteString.copyFrom(ByteArray
            .fromHexString(ByteArray.toHexString(nk.toByteArray())))).build();
    IncomingViewingKeyMessage ivk = blockingStubFull.getIncomingViewingKey(vk);
    logger.info("ivk:" + ByteArray.toHexString(ivk.toByteArray()));


  }


  @Test(enabled = false, description = "Shield to shield transaction")
  public void test1Shield2ShieldTransaction() {
    receiverShieldAddressInfo = PublicMethed.generateShieldAddress();
    receiverShieldAddress = receiverShieldAddressInfo.get().getAddress();

    shieldOutList.clear();;
    memo = "Send shield to receiver shield memo in" + System.currentTimeMillis();
    shieldOutList = PublicMethed.addShieldOutputList(shieldOutList,receiverShieldAddress,
        "" + (sendNote.getValue() - zenTokenFee),memo);
    Assert.assertTrue(PublicMethed.sendShieldCoin(
        null,0,
        sendShieldAddressInfo.get(), notes.getNoteTxs(0),
        shieldOutList,
        null,0,
        zenTokenOwnerKey,blockingStubFull));

    PublicMethed.waitProduceNextBlock(blockingStubFull);
    notes = PublicMethed.listShieldNote(receiverShieldAddressInfo,blockingStubFull);
    receiverNote = notes.getNoteTxs(0).getNote();
    logger.info("Receiver note:" + receiverNote.toString());
    Assert.assertTrue(receiverNote.getValue() == sendNote.getValue() - zenTokenFee);

  }

  /**
   * constructor.
   */

  @AfterClass(enabled = true)
  public void shutdown() throws InterruptedException {
    PublicMethed.transferAsset(foundationZenTokenAddress, tokenId,
        PublicMethed.getAssetIssueValue(zenTokenOwnerAddress,
            PublicMethed.queryAccount(foundationZenTokenKey, blockingStubFull).getAssetIssuedID(),
            blockingStubFull), zenTokenOwnerAddress, zenTokenOwnerKey, blockingStubFull);
    if (channelFull != null) {
      channelFull.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (channelSolidity != null) {
      channelSolidity.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (channelSolidity1 != null) {
      channelSolidity1.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}