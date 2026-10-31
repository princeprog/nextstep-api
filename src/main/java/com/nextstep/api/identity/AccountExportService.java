package com.nextstep.api.identity;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextstep.api.applications.ApplicationRepository;
import com.nextstep.api.interviews.InterviewRepository;
import com.nextstep.api.tasks.TaskRepository;

@Service
public class AccountExportService {

    private final AccountService accounts;
    private final ApplicationRepository applications;
    private final TaskRepository tasks;
    private final InterviewRepository interviews;
    private final Clock clock;

    public AccountExportService(AccountService accounts, ApplicationRepository applications, TaskRepository tasks,
                                InterviewRepository interviews, Clock clock) {
        this.accounts = accounts;
        this.applications = applications;
        this.tasks = tasks;
        this.interviews = interviews;
        this.clock = clock;
    }

    /** The account and every record it owns, including archived applications and their tasks and interviews. */
    @Transactional(readOnly = true)
    public AccountExport export(UUID accountId) {
        Account account = accounts.get(accountId);
        return new AccountExport(
                AccountExport.FORMAT,
                clock.instant().toString(),
                AccountExport.AccountData.of(account),
                applications.findAllByOwnerIdOrderByCreatedAtAsc(accountId).stream().map(AccountExport.ApplicationData::of).toList(),
                tasks.findAllByOwnerIdOrderByCreatedAtAsc(accountId).stream().map(AccountExport.TaskData::of).toList(),
                interviews.findAllByOwnerIdOrderByCreatedAtAsc(accountId).stream().map(AccountExport.InterviewData::of).toList());
    }
}
