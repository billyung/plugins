/*
 * Copyright (c) 2005-2010 Grameen Foundation USA
 * All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * See also http://www.apache.org/licenses/LICENSE-2.0.html for an
 * explanation of the license and how it is applied.
 */

package ke.co.safaricom;

import java.io.InputStream;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.mifos.StandardImport;
import org.mifos.accounts.api.AccountPaymentParametersDto;
import org.mifos.accounts.api.AccountReferenceDto;
import org.mifos.accounts.api.InvalidPaymentReason;
import org.mifos.accounts.api.PaymentTypeDto;
import org.mifos.spi.ParseResultDto;

/**
 * This class implements mpesa plugin which export transactions from an XLS sheet to Mifos database.
 * It uses the standard mifos API/SPI. <br>
 * <a href='http://www.mifos.org/developers/wiki/PluginManagement'>http://www.mifos.org/developers/wiki/PluginManagement</a>
 * 
 * 
 * 
 */
public class MPesaXlsImporter extends StandardImport {
    private static final String IMPORT_TRANSACTION_ORDER = "ImportTransactionOrder";
    private static final String EXPECTED_STATUS = "Completed";
    protected static final String PAYMENT_TYPE = "MPESA/ZAP";
    protected static final int RECEIPT = 0;
    protected static final int TRANSACTION_DATE = 1;
    protected static final int DETAILS = 2;
    protected static final int STATUS = 3;
    protected static final int WITHDRAWN = 4;
    protected static final int PAID_IN = 5;
    protected static final int BALANCE = 6;
    protected static final int BALANCE_CONFIRMED = 7;
    protected static final int TRANSACTION_TYPE = 8;
    protected static final int OTHER_PARTY_INFO = 9;
    protected static final int TRANSACTION_PARTY_DETAILS = 10;
    protected static final int MAX_CELL_NUM = 11;

    private static Map<AccountReferenceDto, BigDecimal> cumulativeAmountByAccount;
    private static List<AccountPaymentParametersDto> pmts;
    private static List<String> errorsList;
    private static List<String> importTransactionOrder;
    private static int successfullyParsedRows;
    @Override
    public String getDisplayName() {
        return "M-PESA Excel 97(-2007)";
    }

    @SuppressWarnings("unchecked")
    protected List<String> getImportTransactionOrder() {
        if (importTransactionOrder == null) {
            final String importTransactionOrderKey = MPesaXlsImporter.class.getCanonicalName() + "."
                    + IMPORT_TRANSACTION_ORDER;
            importTransactionOrder = (List<String>) getAccountService()
                    .getMifosConfiguration(importTransactionOrderKey);
            if (importTransactionOrder == null) {
                importTransactionOrder = new ArrayList<String>();
            }
        }
        return importTransactionOrder;
    }
    
    private String cellStringValue(Cell cell) {
    	if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
    		return Double.toString(cell.getNumericCellValue());
    	} else {
    		return cell.getStringCellValue();
    	}
    }

    @Override
    public ParseResultDto parse(final InputStream input) {
        cumulativeAmountByAccount = new HashMap<AccountReferenceDto, BigDecimal>();
        pmts = new ArrayList<AccountPaymentParametersDto>();
        errorsList = new LinkedList<String>();
        successfullyParsedRows = 0;

        try {
            final Iterator<Row> rowIterator = new HSSFWorkbook(input).getSheetAt(0).iterator();
            int friendlyRowNum = 0;
            Row row;

            setPaymentType();

            skipToTransactionData(rowIterator);

            if (!errorsList.isEmpty()) {
                return new ParseResultDto(errorsList, pmts);
            }

            /* Parse transaction data */

            while (rowIterator.hasNext()) {
                try {
                    row = rowIterator.next();

                    friendlyRowNum = row.getRowNum() + 1;

                    if (!isRowValid(row, friendlyRowNum, errorsList)) {
                        continue;
                    }
                    
                    String receipt = cellStringValue(row.getCell(RECEIPT));

                    Date transDate;
                    try {
                        transDate = getDate(row.getCell(TRANSACTION_DATE));
                    } catch (Exception e) {
                        errorsList.add("Date in Row " + friendlyRowNum
                                + "  does not begin with expected format (YYYY-MM-DD), it contains "
                                + row.getCell(TRANSACTION_DATE).getStringCellValue());
                        continue;
                    }

                    final LocalDate paymentDate = LocalDate.fromDateFields(transDate);
                    String transactionPartyDetails = null;
                    
                    if(row.getCell(TRANSACTION_PARTY_DETAILS).getCellType() == Cell.CELL_TYPE_NUMERIC) {
                        transactionPartyDetails = row.getCell(TRANSACTION_PARTY_DETAILS).getNumericCellValue() +"";
                        if(transactionPartyDetails.endsWith(".0")){
                            transactionPartyDetails = transactionPartyDetails.replace(".0", "");
                        } else {
                            throw new IllegalArgumentException("Unknown format of cell "+ TRANSACTION_PARTY_DETAILS);
                        }
                    } else if (row.getCell(TRANSACTION_PARTY_DETAILS).getCellType() == Cell.CELL_TYPE_STRING) {
                    transactionPartyDetails = row.getCell(TRANSACTION_PARTY_DETAILS).getStringCellValue();
                    }
                    List<String> parameters = checkAndGetValues(transactionPartyDetails);

                    String governmentId = parameters.get(0);
                    List<String> loanPrds = new LinkedList<String>();
                    String lastInTheOrderProdSName = parameters.get(parameters.size() - 1);
                    loanPrds.addAll(parameters.subList(1, parameters.size() - 1));

                    checkBlank(governmentId, "Government ID", friendlyRowNum);
                    checkBlank(lastInTheOrderProdSName, "Savings product short name", friendlyRowNum);

                    BigDecimal paidInAmount = BigDecimal.ZERO;

                    // FIXME: possible data loss converting double to BigDecimal?
                    paidInAmount = BigDecimal.valueOf(row.getCell(PAID_IN).getNumericCellValue());
                    boolean cancelTransactionFlag = false;

                    List<AccountPaymentParametersDto> loanPaymentList = new ArrayList<AccountPaymentParametersDto>();

                    for (String loanPrd : loanPrds) {
                        BigDecimal loanAccountPaymentAmount = BigDecimal.ZERO;
                        BigDecimal loanAccountTotalDueAmount = BigDecimal.ZERO;

                        final AccountReferenceDto loanAccountReference = getLoanAccount(governmentId, loanPrd);
                        
                     // skip not found accounts as per specs P1 4.9 M-Pesa plugin
                        if(loanAccountReference == null){
                            continue;
                        }
                        
                        loanAccountTotalDueAmount = getTotalPaymentDueAmount(loanAccountReference);

                        if (paidInAmount.compareTo(BigDecimal.ZERO) > 0) {
                            if (paidInAmount.compareTo(loanAccountTotalDueAmount) > 0) {
                                loanAccountPaymentAmount = loanAccountTotalDueAmount;
                                paidInAmount = paidInAmount.subtract(loanAccountTotalDueAmount);
                            } else {
                                loanAccountPaymentAmount = paidInAmount;
                                paidInAmount = BigDecimal.ZERO;
                            }
                        } else {
                            loanAccountPaymentAmount = BigDecimal.ZERO;
                        }

                        AccountPaymentParametersDto cumulativeLoanPayment = createPaymentParametersDto(
                                loanAccountReference, loanAccountPaymentAmount, paymentDate);

                        if (!isPaymentValid(cumulativeLoanPayment, friendlyRowNum)) {
                            cancelTransactionFlag = true;
                            break;
                        }
                        loanPaymentList.add(new AccountPaymentParametersDto(getUserReferenceDto(),
                                loanAccountReference, loanAccountPaymentAmount, paymentDate, getPaymentTypeDto(), "", new LocalDate(), receipt));

                    }

                    if (cancelTransactionFlag) {
                        continue;
                    }

                    BigDecimal lastInOrderAmount;
                    AccountReferenceDto lastInOrderAcc;
                    lastInOrderAcc = getSavingsAccount(governmentId, lastInTheOrderProdSName);
                    
                    if(lastInOrderAcc == null) {
                    	lastInOrderAcc = getLoanAccount(governmentId, lastInTheOrderProdSName);
                    	if (lastInOrderAcc != null) {
                    		BigDecimal totalPaymentDueAmount = getTotalPaymentDueAmount(lastInOrderAcc);
                    		if(paidInAmount.compareTo(totalPaymentDueAmount) != 0) {
                    			errorsList.add("Last account is a laon account but the total payment amount is less than amount paid in. Input line number: "+ friendlyRowNum);
                    			continue;  
                    		}
                    	}
                    }
                    
                    if(lastInOrderAcc == null) {
                        errorsList.add("No acccount found. Input line number: " + friendlyRowNum);
                        continue;
                    }

                    if (paidInAmount.compareTo(BigDecimal.ZERO) > 0) {
                        lastInOrderAmount = paidInAmount;
                        paidInAmount = BigDecimal.ZERO;
                    } else {
                        lastInOrderAmount = BigDecimal.ZERO;
                    }
                    
                    final AccountPaymentParametersDto cumulativePaymentlastAcc = createPaymentParametersDto(lastInOrderAcc,
                            lastInOrderAmount, paymentDate);
                    final AccountPaymentParametersDto lastInTheOrderAccPayment = new AccountPaymentParametersDto(
                            getUserReferenceDto(), lastInOrderAcc, lastInOrderAmount, paymentDate, getPaymentTypeDto(), "", new LocalDate(), receipt);

                    if (!isPaymentValid(cumulativePaymentlastAcc, friendlyRowNum)) {
                        continue;
                    }
                    successfullyParsedRows+=1;
                    for (AccountPaymentParametersDto loanPayment : loanPaymentList) {
                        pmts.add(loanPayment);
                    }
                    pmts.add(lastInTheOrderAccPayment);
                } catch (Exception e) {
                    /* catch row specific exception and continue for other rows */
                    errorsList.add(e.getMessage() + ". Input line number: " + friendlyRowNum);
                    continue;
                }
            }
        } catch (Exception e) {
            /* Catch any exception in the process */
            e.printStackTrace();
            errorsList.add(e.getMessage() + ". Got error before reading rows");

        }
        return new ParseResultDto(errorsList, pmts);
    }

    /**
     * Get the parameter list from transaction party details field or ImportTransactionOrder property. This method is
     * executed after validation of input.
     * 
     * @param transactionPartyDetails
     * @return list of parameters
     */
    protected List<String> checkAndGetValues(String transactionPartyDetails) {
        List<String> parameters = new LinkedList<String>();
        String[] result = transactionPartyDetails.split(" ");
        parameters.addAll(Arrays.asList(result));
        if (result.length == 1) {
            List<String> importTransactionOrder = getImportTransactionOrder();
            if (importTransactionOrder == null || importTransactionOrder.isEmpty()) {
                throw new MPesaXlsImporterException("No Product name in \"Transaction Party Details\" field and "
                        + IMPORT_TRANSACTION_ORDER + " property is not set");

            }
            parameters.addAll(importTransactionOrder);
        }
        return parameters;
    }

    private AccountPaymentParametersDto createPaymentParametersDto(final AccountReferenceDto accountReference,
            final BigDecimal paymentAmount, final LocalDate paymentDate) {
        BigDecimal totalPaymentAmountForAccount = addToRunningTotalForAccount(paymentAmount, cumulativeAmountByAccount,
                accountReference);
        return new AccountPaymentParametersDto(getUserReferenceDto(), accountReference, totalPaymentAmountForAccount,
                paymentDate, getPaymentTypeDto(), "");
    }

    /**
     * @throws Exception
     */
    private void setPaymentType() throws Exception {
        final PaymentTypeDto paymentType = findPaymentType(PAYMENT_TYPE);

        if (paymentType == null) {
            throw new MPesaXlsImporterException("Payment type " + PAYMENT_TYPE + " not found. Have you configured"
                    + " this payment type?");
        }
        setPaymentTypeDto(paymentType);
    }

    private boolean isRowValid(final Row row, final int friendlyRowNum, List<String> errorsList) throws Exception {
        String missingDataMsg = "Row " + friendlyRowNum + " is missing data: ";
        if (row.getLastCellNum() < MAX_CELL_NUM) {
            errorsList.add(missingDataMsg + "not enough fields.");
            return false;
        }
        if (null == row.getCell(TRANSACTION_DATE)) {
            errorsList.add(missingDataMsg + "Date field is empty.");
            return false;
        }
        if (null == row.getCell(TRANSACTION_PARTY_DETAILS)) {
            errorsList.add(missingDataMsg + "\"Transaction party details\" field is empty.");
            return false;
        }
        if (null == row.getCell(PAID_IN)) {
            errorsList.add(missingDataMsg + "\"Paid in\" field is empty.");
            return false;
        }
        if (row.getCell(STATUS) == null) {
            errorsList.add(missingDataMsg + "Status field is empty");
            return false;
        }
        else {
            if (!row.getCell(STATUS).getStringCellValue().trim().equals(EXPECTED_STATUS)) {
                errorsList.add("Status in row " + friendlyRowNum + " is " + row.getCell(STATUS) + " instead of "
                        + EXPECTED_STATUS);
                return false;
            }
            
            String receiptNumber = cellStringValue(row.getCell(RECEIPT));
			if (receiptNumber != null && !receiptNumber.isEmpty()) {
            	if (getAccountService().receiptExists(receiptNumber)) {
            		errorsList.add(String.format("Row <%d> error - %s - Transactions with same Receipt ID have already been imported",
            				friendlyRowNum, receiptNumber));
            		return false;
            	}
            }
        }
        return true;
    }

    private void checkBlank(final String value, final String name, final int friendlyRowNum) {
        if (StringUtils.isBlank(value)) {
            errorsList.add(name + " could not be extracted from row " + friendlyRowNum);
        }
    }

    private boolean isPaymentValid(final AccountPaymentParametersDto cumulativePayment, final int friendlyRowNum)
            throws Exception {
        final List<InvalidPaymentReason> errors = getAccountService().validatePayment(cumulativePayment);

        if (!errors.isEmpty()) {
            for (InvalidPaymentReason error : errors) {
                switch (error) {
                case INVALID_DATE:
                    errorsList.add("Invalid transaction date in row " + friendlyRowNum);
                    break;
                case UNSUPPORTED_PAYMENT_TYPE:
                    errorsList.add("Unsupported payment type in row " + friendlyRowNum);
                    break;
                case INVALID_PAYMENT_AMOUNT:
                    errorsList.add("Invalid payment amount in row " + friendlyRowNum);
                    break;
                case INVALID_LOAN_STATE:
                    errorsList.add("Invalid account state in row " + friendlyRowNum);
                    break;
                default:
                    errorsList.add("Invalid payment in row " + friendlyRowNum + " (reason unknown).");
                    break;
                }
            }
            return false;
        }
        return true;
    }

    private BigDecimal getTotalPaymentDueAmount(final AccountReferenceDto advanceLoanAccount) throws Exception {
        return getAccountService().getTotalPaymentDueAmount(advanceLoanAccount);

    }

    protected AccountReferenceDto getSavingsAccount(final String governmentId, final String savingsProductShortName) throws Exception {
        AccountReferenceDto account = null;
        try {
            account = getAccountService().lookupSavingsAccountReferenceFromClientGovernmentIdAndSavingsProductShortName(governmentId, savingsProductShortName);
        } catch (Exception e) {
            if (!e.getMessage().equals("savings not found for client government id " + governmentId + " and savings product short name " + savingsProductShortName)) {
                throw e;
            }
        }
        return account;
    }

    protected AccountReferenceDto getLoanAccount(final String governmentId, final String loanProductShortName) throws Exception {
        AccountReferenceDto account = null;
        try {
            account = getAccountService().lookupLoanAccountReferenceFromClientGovernmentIdAndLoanProductShortName(governmentId, loanProductShortName);
        } catch (Exception e) {
            if(!e.getMessage().equals("loan not found for client government id " + governmentId  + " and loan product short name " + loanProductShortName)) {
                throw e;
            }
        }
        return account;
    }

    private void skipToTransactionData(final Iterator<Row> rowIterator) {
        boolean skippingRowsBeforeTransactionData = true;
        while (errorsList.isEmpty() && skippingRowsBeforeTransactionData) {
            if (!rowIterator.hasNext()) {
                errorsList.add("No rows found with import data.");
                break;
            }
            final Row row = rowIterator.next();
            if (row.getCell(0).getStringCellValue().trim().equals("Transactions")) {
                skippingRowsBeforeTransactionData = false;
                /* skip row with column descriptions */
                rowIterator.next();
            }
        }
    }

    protected static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    protected Date getDate(final Cell transDateCell) throws ParseException {
        Date date = null;
        if (transDateCell.getCellType() == Cell.CELL_TYPE_STRING) {
            final SimpleDateFormat dateAsText = new SimpleDateFormat(DATE_FORMAT, Locale.ENGLISH);
            dateAsText.setLenient(false);
            date = dateAsText.parse(transDateCell.getStringCellValue());
        } else if (transDateCell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            date = transDateCell.getDateCellValue();
        }
        return date;
    }

    /**
     * M-PESA imports have multiple transactions per row. Two loan accounts and
     * one savings account, I think.
     * 
     * See <a href="http://mifosforge.jira.com/browse/MIFOS-2909">MIFOS-2909</a>.
     */
    @Override
    public int getSuccessfullyParsedRows() {
        return successfullyParsedRows;
    }

    class MPesaXlsImporterException extends RuntimeException {

        private static final long serialVersionUID = 731436914098659043L;

        MPesaXlsImporterException(final String msg) {
            super(msg);
        }

    }
}
