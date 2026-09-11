package io.dbtower.workbench.internal;

import io.dbtower.registry.RegistryService;
import io.dbtower.workbench.internal.WorkbenchService.WorkbenchRejection;
import io.dbtower.workbench.internal.domain.ChatMessage;
import io.dbtower.workbench.internal.domain.SqlVersion;
import io.dbtower.workbench.internal.domain.Worksheet;
import io.dbtower.workbench.internal.persistence.ChatMessageRepository;
import io.dbtower.workbench.internal.persistence.SqlVersionRepository;
import io.dbtower.workbench.internal.persistence.WorksheetRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 워크시트·SQL 버전·타임라인. TOI류 도구의 "페이지마다 대화, 수정마다 버전 카드, 되돌리기도 새 버전"을 SQL에 옮겼다.
 *
 * <p>워크시트는 만든 사람의 것이다(다른 사용자에게는 미등록과 같은 404). 팀 범위가 바뀌면 자기 워크시트라도
 * 그 인스턴스를 더는 볼 수 없으니 매 진입에서 {@link RegistryService#findById}를 다시 통과시킨다.
 */
@Service
public class WorksheetService {

    static final String SOURCE_AI = "AI";
    static final String SOURCE_RUN = "RUN";
    static final String SOURCE_RESTORE = "RESTORE";
    private static final int TITLE_MAX = 100;

    private final RegistryService registry;
    private final WorksheetRepository worksheets;
    private final SqlVersionRepository versions;
    private final ChatMessageRepository chats;

    public WorksheetService(RegistryService registry, WorksheetRepository worksheets, SqlVersionRepository versions,
                            ChatMessageRepository chats) {
        this.registry = registry;
        this.worksheets = worksheets;
        this.versions = versions;
        this.chats = chats;
    }

    public record WorksheetView(Long id, Long instanceId, String title, String currentSql, Integer latestVersion,
                                LocalDateTime updatedAt) {
    }

    /** 체크포인트 카드 — 제목·만든 사람·시각이 붙는다(TOI류 도구의 커밋 같은 카드). */
    public record VersionView(Long id, int versionNo, String sql, String source, Long chatMessageId,
                              Integer restoredFrom, String title, String principal, LocalDateTime createdAt) {
    }

    /** AI 답변이 SQL을 냈으면 그 체크포인트(version)가 카드 안에 붙는다. */
    public record MessageView(Long id, String role, String content, String sql, String tier, String kind,
                              List<String> unknownTables, boolean valuesShared, VersionView version,
                              LocalDateTime createdAt) {
    }

    /** 대화와 버전을 한 줄로 — AI 버전은 그 답변 카드 안에 번호로 붙고, 실행·되돌리기 버전만 따로 줄을 차지한다. */
    public record TimelineItem(String type, LocalDateTime at, MessageView message, VersionView version) {
    }

    @Transactional(readOnly = true)
    public List<WorksheetView> list(Long instanceId) {
        registry.findById(instanceId);
        return worksheets.findByPrincipalAndInstanceIdAndArchivedFalseOrderByUpdatedAtDesc(principal(), instanceId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public WorksheetView create(Long instanceId, String title) {
        registry.findById(instanceId);
        String name = title == null || title.isBlank()
                ? "워크시트 " + (worksheets.countByPrincipalAndInstanceId(principal(), instanceId) + 1)
                : cleanTitle(title);
        return view(worksheets.save(new Worksheet(principal(), instanceId, name)));
    }

    @Transactional
    public WorksheetView update(Long worksheetId, String title, String currentSql) {
        Worksheet ws = requireOwned(worksheetId);
        if (title != null && !title.isBlank()) {
            ws.rename(cleanTitle(title));
        }
        if (currentSql != null) {
            ws.updateSql(currentSql);
        }
        return view(worksheets.save(ws));
    }

    @Transactional
    public void archive(Long worksheetId) {
        Worksheet ws = requireOwned(worksheetId);
        ws.archive();
        worksheets.save(ws);
    }

    Worksheet requireOwned(Long worksheetId) {
        Worksheet ws = worksheets.findById(worksheetId)
                .filter(w -> !w.isArchived() && w.getPrincipal().equals(principal()))
                .orElseThrow(() -> new WorkbenchRejection(404, "워크시트를 찾을 수 없습니다: " + worksheetId, null));
        registry.findById(ws.getInstanceId());
        return ws;
    }

    /** 실행 요청의 워크시트가 그 인스턴스의 것인지까지 본다 — 다른 인스턴스 워크시트에 버전을 끼워 넣지 못하게. */
    Worksheet requireOwned(Long worksheetId, Long instanceId) {
        Worksheet ws = requireOwned(worksheetId);
        if (!ws.getInstanceId().equals(instanceId)) {
            throw new WorkbenchRejection(404, "워크시트를 찾을 수 없습니다: " + worksheetId, null);
        }
        return ws;
    }

    @Transactional
    VersionView addVersion(Worksheet ws, String sql, String source, Long chatMessageId, Integer restoredFrom,
                           String title) {
        int next = versions.findTopByWorksheetIdOrderByVersionNoDesc(ws.getId())
                .map(v -> v.getVersionNo() + 1).orElse(1);
        SqlVersion saved = versions.save(new SqlVersion(ws.getId(), next, sql, source, chatMessageId, restoredFrom,
                title, principal()));
        // AI 제안은 사람이 편집기로 가져가기 전까지 편집기를 바꾸지 않는다. 실행·되돌리기는 편집기 내용이 곧 그 버전이다
        if (SOURCE_AI.equals(source)) {
            ws.touch();
        } else {
            ws.updateSql(sql);
        }
        worksheets.save(ws);
        return view(saved);
    }

    /** 성공한 실행을 버전으로 남긴다. 마지막 버전과 같은 문장이면 새 버전을 만들지 않는다(같은 쿼리 재실행이 이력을 도배하지 않게). */
    @Transactional
    public Optional<VersionView> recordRun(Long worksheetId, Long instanceId, String sql) {
        Worksheet ws = requireOwned(worksheetId, instanceId);
        Optional<SqlVersion> latest = versions.findTopByWorksheetIdOrderByVersionNoDesc(ws.getId());
        if (latest.isPresent() && latest.get().getSql().strip().equals(sql.strip())) {
            ws.updateSql(sql);
            worksheets.save(ws);
            return Optional.empty();
        }
        return Optional.of(addVersion(ws, sql, SOURCE_RUN, null, null, "직접 실행한 SQL"));
    }

    @Transactional
    public VersionView restore(Long worksheetId, int versionNo) {
        Worksheet ws = requireOwned(worksheetId);
        SqlVersion source = versions.findByWorksheetIdAndVersionNo(ws.getId(), versionNo)
                .orElseThrow(() -> new WorkbenchRejection(404, "버전을 찾을 수 없습니다: v" + versionNo, null));
        return addVersion(ws, source.getSql(), SOURCE_RESTORE, null, versionNo, "v" + versionNo + "로 되돌림");
    }

    @Transactional(readOnly = true)
    public List<TimelineItem> timeline(Long worksheetId) {
        Worksheet ws = requireOwned(worksheetId);
        List<SqlVersion> all = versions.findByWorksheetIdOrderByVersionNoAsc(ws.getId());
        Map<Long, SqlVersion> versionByMessage = all.stream()
                .filter(v -> v.getChatMessageId() != null)
                .collect(Collectors.toMap(SqlVersion::getChatMessageId, v -> v, (a, b) -> a));
        List<TimelineItem> items = new ArrayList<>();
        for (ChatMessage m : chats.findByWorksheetIdOrderByCreatedAtAsc(ws.getId())) {
            items.add(new TimelineItem("MESSAGE", m.getCreatedAt(), messageView(m, versionByMessage.get(m.getId())), null));
        }
        all.stream()
                .filter(v -> !SOURCE_AI.equals(v.getSource()))
                .forEach(v -> items.add(new TimelineItem("VERSION", v.getCreatedAt(), null, view(v))));
        items.sort(Comparator.comparing(TimelineItem::at));
        return items;
    }

    private WorksheetView view(Worksheet ws) {
        Integer latest = ws.getId() == null ? null
                : versions.findTopByWorksheetIdOrderByVersionNoDesc(ws.getId()).map(SqlVersion::getVersionNo).orElse(null);
        return new WorksheetView(ws.getId(), ws.getInstanceId(), ws.getTitle(), ws.getCurrentSql(), latest,
                ws.getUpdatedAt());
    }

    private static VersionView view(SqlVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getSql(), v.getSource(), v.getChatMessageId(),
                v.getRestoredFrom(), v.getTitle(), v.getPrincipal(), v.getCreatedAt());
    }

    private static MessageView messageView(ChatMessage m, SqlVersion version) {
        List<String> unknown = m.getUnknownTables() == null ? List.of() : Arrays.asList(m.getUnknownTables().split(","));
        return new MessageView(m.getId(), m.getRole(), m.getContent(), m.getProposedSql(), m.getTier(), m.getKind(),
                unknown, m.isValuesShared(), version == null ? null : view(version), m.getCreatedAt());
    }

    private static String cleanTitle(String title) {
        String t = title.strip();
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
    }

    static String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
