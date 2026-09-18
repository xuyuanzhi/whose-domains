package info.wesite.admin.controller;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.diagnostics.IssueStore;
import info.wesite.core.view.ResponseJson;

@RestController
@RequestMapping("/admin/issues")
@AccessControl(level=AccessControl.Level.SESSION)
public class IssueController {
    private final IssueStore store;
    public IssueController(IssueStore store) { this.store=store; }
    @GetMapping("/list")
    public ResponseJson<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String status,@RequestParam(required=false) String app,
        @RequestParam(required=false) String source,@RequestParam(required=false) Long from,
        @RequestParam(required=false) Long to,@RequestParam(required=false) String keyword) {
        bounds(page,size);
        return ResponseJson.success(store.list(page,size,status,app,source,from,to,keyword));
    }
    @GetMapping("/{id}") public ResponseJson<?> detail(@PathVariable String id) { return ResponseJson.success(store.detail(id)); }
    @GetMapping("/{id}/events") public ResponseJson<?> events(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        bounds(page,size); return ResponseJson.success(store.children(id,false,page,size));
    }
    @GetMapping("/{id}/notes") public ResponseJson<?> notes(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        bounds(page,size); return ResponseJson.success(store.children(id,true,page,size));
    }
    public record Update(String status,String note,String resolvedRelease,Long version) {}
    @PostMapping("/{id}/status") public ResponseJson<?> update(@PathVariable String id,@RequestBody Update update) {
        if (update.version()==null || update.status()==null) throw new IllegalArgumentException();
        store.manage(id,update.version(),update.status(),update.note(),update.resolvedRelease(),UserHolder.get().getId());
        return ResponseJson.success();
    }
    private static void bounds(int page,int size) { if (page<1 || page>1000000 || size<1 || size>100) throw new IllegalArgumentException(); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(ResponseJson.failure("参数无效")); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(ResponseJson.failure("问题不存在或已超过保留期")); }
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(ResponseJson.failure("问题已更新，请刷新后重新保存")); }
}
