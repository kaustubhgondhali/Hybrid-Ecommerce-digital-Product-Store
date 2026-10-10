package com.hybrid.platform.notification.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.notification.MailService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class NotificationAdminController {

    public record TestRequest(@NotBlank String to) {
    }

    private final MailService mail;

    public NotificationAdminController(MailService mail) {
        this.mail = mail;
    }

    @GetMapping("/settings/smtp")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public MailService.SmtpView get(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return mail.get(sf);
    }

    @PutMapping("/settings/smtp")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public MailService.SmtpView update(@PathVariable String storefront, @RequestBody MailService.SmtpInput input) {
        return mail.update(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PostMapping("/settings/smtp/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void test(@PathVariable String storefront, @Valid @RequestBody TestRequest req) {
        mail.sendTest(CurrentUser.admin(Storefront.fromPath(storefront)), req.to());
    }

    @PostMapping("/notifications/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void retry(@PathVariable String storefront, @PathVariable Long id) {
        mail.retry(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public PageResponse<MailService.LogEntry> log(@PathVariable String storefront,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return mail.log(sf, page, size);
    }
}
