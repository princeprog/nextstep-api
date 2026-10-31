package com.nextstep.api.identity;

import java.time.Instant;

import com.nextstep.api.common.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account extends BaseEntity {

    @Enumerated(EnumType.STRING)
    private AccountKind kind;
    private String displayName;
    private String email;
    private String passwordHash;
    private String timeZone;
    private Instant demoExpiresAt;
    private Instant emailVerifiedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public AccountKind getKind() { return kind; }
    public void setKind(AccountKind kind) { this.kind = kind; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Instant getDemoExpiresAt() { return demoExpiresAt; }
    public void setDemoExpiresAt(Instant demoExpiresAt) { this.demoExpiresAt = demoExpiresAt; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
    /** Demo workspaces have no email, so verification never limits them. */
    public boolean hasVerifiedAccess() { return kind == AccountKind.DEMO || emailVerifiedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

