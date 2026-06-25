package com.aifinance.adapter.in.web;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransferController {
    private final TransferPort transferPort;
    private final AccountPort accountPort;

    public TransferController(TransferPort transferPort, AccountPort accountPort) {
        this.transferPort = transferPort;
        this.accountPort = accountPort;
    }


    @GetMapping("/transfers")
    public List<Transfer> getTransfers() {
        return transferPort.findAll();
    }

    @GetMapping("/accounts")
    public List<Account> getAccounts() {
        return accountPort.findAll();
    }

}

