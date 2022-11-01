package org.tron.core.db;

import static org.tron.common.runtime.InternalTransaction.TrxType.TRX_CONTRACT_CALL_TYPE;
import static org.tron.common.runtime.InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE;

import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.tron.common.runtime.InternalTransaction.TrxType;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.DecodeUtil;
import org.tron.common.utils.ForkController;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.WalletUtil;
import org.tron.core.Constant;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ReceiptCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ReceiptCheckErrException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.store.AbiStore;
import org.tron.core.store.AccountStore;
import org.tron.core.store.CodeStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.Common;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

@Slf4j(topic = "DB")
public class TransactionTrace {

  private TransactionCapsule trx;

  private ReceiptCapsule receipt;

  private StoreFactory storeFactory;

  private DynamicPropertiesStore dynamicPropertiesStore;

  private ContractStore contractStore;

  private AccountStore accountStore;

  private CodeStore codeStore;

  private AbiStore abiStore;

  private EnergyProcessor energyProcessor;

  private TrxType trxType;

  private long txStartTimeInMs;

  private Runtime runtime;

  private ForkController forkController;

  @Getter
  private TransactionContext transactionContext;
  @Getter
  @Setter
  private TimeResultType timeResultType = TimeResultType.NORMAL;
  @Getter
  @Setter
  private boolean netFeeForBandwidth = true;

  public TransactionTrace(TransactionCapsule trx, StoreFactory storeFactory,
      Runtime runtime) {
    this.trx = trx;
    Transaction.Contract.ContractType contractType = this.trx.getInstance().getRawData()
        .getContract(0).getType();
    switch (contractType.getNumber()) {
      case ContractType.TriggerSmartContract_VALUE:
        trxType = TRX_CONTRACT_CALL_TYPE;
        break;
      case ContractType.CreateSmartContract_VALUE:
        trxType = TRX_CONTRACT_CREATION_TYPE;
        break;
      default:
        trxType = TrxType.TRX_PRECOMPILED_TYPE;
    }
    this.storeFactory = storeFactory;
    this.dynamicPropertiesStore = storeFactory.getChainBaseManager().getDynamicPropertiesStore();
    this.contractStore = storeFactory.getChainBaseManager().getContractStore();
    this.codeStore = storeFactory.getChainBaseManager().getCodeStore();
    this.abiStore = storeFactory.getChainBaseManager().getAbiStore();
    this.accountStore = storeFactory.getChainBaseManager().getAccountStore();

    this.receipt = new ReceiptCapsule(Sha256Hash.ZERO_HASH);
    this.energyProcessor = new EnergyProcessor(dynamicPropertiesStore, accountStore);
    this.runtime = runtime;
    this.forkController = new ForkController();
    forkController.init(storeFactory.getChainBaseManager());
  }

  public TransactionCapsule getTrx() {
    return trx;
  }

  private boolean needVM() {
    return this.trxType == TRX_CONTRACT_CALL_TYPE
        || this.trxType == TRX_CONTRACT_CREATION_TYPE;
  }

  public void init(BlockCapsule blockCap) {
    init(blockCap, false);
  }

  //pre transaction check
  public void init(BlockCapsule blockCap, boolean eventPluginLoaded) {
    txStartTimeInMs = System.currentTimeMillis();
    transactionContext = new TransactionContext(blockCap, trx, storeFactory, false,
        eventPluginLoaded);
  }

  public void checkIsConstant() throws ContractValidateException, VMIllegalException {
    if (dynamicPropertiesStore.getAllowTvmConstantinople() == 1) {
      return;
    }
    TriggerSmartContract triggerContractFromTransaction = ContractCapsule
        .getTriggerContractFromTransaction(this.getTrx().getInstance());
    if (TRX_CONTRACT_CALL_TYPE == this.trxType) {
      ContractCapsule contract = contractStore
          .get(triggerContractFromTransaction.getContractAddress().toByteArray());
      if (contract == null) {
        throw new ContractValidateException(String.format("contract: %s is not in contract store",
            StringUtil.encode58Check(triggerContractFromTransaction
                .getContractAddress().toByteArray())));

      }
      ABI abi = contract.getInstance().getAbi();
      if (WalletUtil.isConstant(abi, triggerContractFromTransaction)) {
        throw new VMIllegalException("cannot call constant method");
      }
    }
  }

  //set bill
  public void setBill(long energyUsage) {
    if (energyUsage < 0) {
      energyUsage = 0L;
    }
    receipt.setEnergyUsageTotal(energyUsage);
  }

  //set net bill
  public void setNetBill(long netUsage, long netFee) {
    receipt.setNetUsage(netUsage);
    receipt.setNetFee(netFee);
  }

  public void setNetBillForCreateNewAccount(long netUsage, long netFee) {
    receipt.setNetUsage(netUsage);
    receipt.setNetFee(netFee);
    setNetFeeForBandwidth(false);
  }

  public void addNetBill(long netFee) {
    receipt.addNetFee(netFee);
  }

  public void exec()
      throws ContractExeException, ContractValidateException, VMIllegalException {
    /*  VM execute  */
    runtime.execute(transactionContext);
    setBill(transactionContext.getProgramResult().getEnergyUsed());

//    if (TrxType.TRX_PRECOMPILED_TYPE != trxType) {
//      if (contractResult.OUT_OF_TIME
//          .equals(receipt.getResult())) {
//        setTimeResultType(TimeResultType.OUT_OF_TIME);
//      } else if (System.currentTimeMillis() - txStartTimeInMs
//          > CommonParameter.getInstance()
//          .getLongRunningTime()) {
//        setTimeResultType(TimeResultType.LONG_RUNNING);
//      }
//    }
  }

  public void saveEnergyLeftOfOrigin(long energyLeft) {
    receipt.setOriginEnergyLeft(energyLeft);
  }

  public void saveEnergyLeftOfCaller(long energyLeft) {
    receipt.setCallerEnergyLeft(energyLeft);
  }

  public void finalization() throws ContractExeException {
    try {
      pay();
    } catch (BalanceInsufficientException e) {
      throw new ContractExeException(e.getMessage());
    }
    if (StringUtils.isEmpty(transactionContext.getProgramResult().getRuntimeError())) {
      for (DataWord contract : transactionContext.getProgramResult().getDeleteAccounts()) {
        deleteContract(contract.toTronAddress());
      }
    }
  }

  /**
   * pay actually bill(include ENERGY and storage).
   */
  public void pay() throws BalanceInsufficientException {
    byte[] originAccount;
    byte[] callerAccount;
    long percent = 0;
    long originEnergyLimit = 0;
    switch (trxType) {
      case TRX_CONTRACT_CREATION_TYPE:
        callerAccount = trx.getOwnerAddress();
        originAccount = callerAccount;
        break;
      case TRX_CONTRACT_CALL_TYPE:
        TriggerSmartContract callContract = ContractCapsule
            .getTriggerContractFromTransaction(trx.getInstance());
        ContractCapsule contractCapsule =
            contractStore.get(callContract.getContractAddress().toByteArray());

        callerAccount = callContract.getOwnerAddress().toByteArray();
        originAccount = contractCapsule.getOriginAddress();
        percent = Math
            .max(Constant.ONE_HUNDRED - contractCapsule.getConsumeUserResourcePercent(), 0);
        percent = Math.min(percent, Constant.ONE_HUNDRED);
        originEnergyLimit = contractCapsule.getOriginEnergyLimit();
        break;
      default:
        return;
    }

    // originAccount Percent = 30%
    AccountCapsule origin = accountStore.get(originAccount);
    AccountCapsule caller = accountStore.get(callerAccount);
    if (dynamicPropertiesStore.supportUnfreezeDelay()
        && receipt.getReceipt().getResult().equals(contractResult.SUCCESS)) {

      // just fo caller is not origin, we set the related field for origin account
      if (!caller.getAddress().equals(origin.getAddress())) {
        long originPrevUsage = receipt.getOriginEnergyUsage() * receipt.getOriginEnergyWindowSize();
        long originRepayUsage = (receipt.getOriginEnergyMergedUsage() - origin.getEnergyUsage())
            * origin.getWindowSize(Common.ResourceCode.ENERGY);

        long originUsageAfterRepay = Long.max(0,
            (originPrevUsage - originRepayUsage) / receipt.getOriginEnergyWindowSize());
        long originWindowSizeAfterRepay =
            originUsageAfterRepay == 0 ? 0L : receipt.getOriginEnergyWindowSize();

        origin.setEnergyUsage(originUsageAfterRepay);
        origin.setNewWindowSize(Common.ResourceCode.ENERGY, originWindowSizeAfterRepay);
      }

      long callerPrevUsage = receipt.getCallerEnergyUsage() * receipt.getCallerEnergyWindowSize();
      long callerRepayUsage = (receipt.getCallerEnergyMergedUsage() - caller.getEnergyUsage())
          * caller.getWindowSize(Common.ResourceCode.ENERGY);

      long callerUsageAfterRepay = Long.max(0,
          (callerPrevUsage - callerRepayUsage) / receipt.getCallerEnergyWindowSize());
      long callerWindowSizeAfterRepay =
          callerUsageAfterRepay == 0 ? 0L : receipt.getCallerEnergyWindowSize();
      caller.setEnergyUsage(callerUsageAfterRepay);
      caller.setNewWindowSize(Common.ResourceCode.ENERGY, callerWindowSizeAfterRepay);
    }
    receipt.payEnergyBill(
        dynamicPropertiesStore, accountStore, forkController,
        origin,
        caller,
        percent, originEnergyLimit,
        energyProcessor,
        EnergyProcessor.getHeadSlot(dynamicPropertiesStore));
  }

  public boolean checkNeedRetry() {
    if (!needVM()) {
      return false;
    }
    return trx.getContractRet() != contractResult.OUT_OF_TIME && receipt.getResult()
        == contractResult.OUT_OF_TIME;
  }

  public void check() throws ReceiptCheckErrException {
    if (!needVM()) {
      return;
    }
    if (Objects.isNull(trx.getContractRet())) {
      throw new ReceiptCheckErrException(
          String.format("null resultCode id: %s", trx.getTransactionId()));
    }
    if (!trx.getContractRet().equals(receipt.getResult())) {
      throw new ReceiptCheckErrException(String.format(
          "different resultCode txId: %s, expect: %s, actual: %s",
          trx.getTransactionId(), trx.getContractRet(), receipt.getResult()));
    }
  }

  public ReceiptCapsule getReceipt() {
    return receipt;
  }

  public void setResult() {
    if (!needVM()) {
      return;
    }
    receipt.setResult(transactionContext.getProgramResult().getResultCode());
  }

  public String getRuntimeError() {
    return transactionContext.getProgramResult().getRuntimeError();
  }

  public ProgramResult getRuntimeResult() {
    return transactionContext.getProgramResult();
  }

  public Runtime getRuntime() {
    return runtime;
  }

  public void deleteContract(byte[] address) {
    abiStore.delete(address);
    codeStore.delete(address);
    accountStore.delete(address);
    contractStore.delete(address);
  }

  public static byte[] convertToTronAddress(byte[] address) {
    if (address.length == 20) {
      byte[] newAddress = new byte[21];
      byte[] temp = new byte[]{DecodeUtil.addressPreFixByte};
      System.arraycopy(temp, 0, newAddress, 0, temp.length);
      System.arraycopy(address, 0, newAddress, temp.length, address.length);
      address = newAddress;
    }
    return address;
  }

  public enum TimeResultType {
    NORMAL,
    LONG_RUNNING,
    OUT_OF_TIME
  }
}
