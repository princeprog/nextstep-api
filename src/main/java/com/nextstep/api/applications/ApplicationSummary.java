package com.nextstep.api.applications;

/** Parent application details embedded in task and interview responses. */
public record ApplicationSummary(String id, String company, String jobTitle, String status, boolean archived) {

    public static ApplicationSummary of(Application application) {
        return new ApplicationSummary(application.getId().toString(), application.getCompany(),
                application.getJobTitle(), application.getStatus().name(), application.isArchived());
    }
}

