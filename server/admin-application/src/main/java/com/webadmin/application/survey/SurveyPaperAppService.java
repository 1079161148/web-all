package com.webadmin.application.survey;

import com.webadmin.application.survey.command.SurveyPaperCommand;
import com.webadmin.application.survey.dto.SurveyPaperDTO;
import com.webadmin.application.survey.port.SurveyPaperPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 问卷 / 提纲应用服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyPaperAppService {

    private final SurveyPaperPort paperPort;

    @Transactional
    public Long create(SurveyPaperCommand command) {
        assertCodeAvailable(command.paperCode(), null);
        Long id = paperPort.insert(command);
        log.info("创建问卷 id={} code={} type={}", id, command.paperCode(), command.paperType());
        return id;
    }

    @Transactional
    public void update(long id, SurveyPaperCommand command) {
        paperPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.PAPER_NOT_FOUND, "问卷不存在"));
        assertCodeAvailable(command.paperCode(), id);
        paperPort.update(id, command);
    }

    @Transactional
    public void delete(long id) {
        paperPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.PAPER_NOT_FOUND, "问卷不存在"));
        paperPort.delete(id);
    }

    /** 详情（含富文本正文）。 */
    public SurveyPaperDTO detail(long id) {
        return paperPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.PAPER_NOT_FOUND, "问卷不存在"));
    }

    /**
     * 版本号 +1 并生成新版本（问卷改版留痕）。
     *
     * <p>不做"就地覆盖"：问卷一旦发布，回收到的数据是按<b>那一版</b>的问题答的，
     * 直接覆盖会让历史数据与题目对应不上。
     */
    @Transactional
    public void bumpVersion(long id) {
        Optional<SurveyPaperDTO> existing = paperPort.findById(id);
        if (existing.isEmpty()) {
            throw new BizException(SurveyErrorCode.PAPER_NOT_FOUND, "问卷不存在");
        }
        SurveyPaperDTO paper = existing.get();
        int nextVersion = (paper.versionNo() == null ? 0 : paper.versionNo()) + 1;
        paperPort.update(id, new SurveyPaperCommand(paper.paperCode(), paper.title(),
                paper.paperType(), nextVersion, paper.content(), "DRAFT", paper.remark()));
        log.info("问卷升版 id={} version={}", id, nextVersion);
    }

    @Transactional
    public int batchCreate(List<SurveyPaperCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                create(commands.get(i));
            } catch (BizException e) {
                throw new BizException(SurveyErrorCode.PAPER_CODE_DUPLICATED,
                        "第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return commands.size();
    }

    private void assertCodeAvailable(String paperCode, Long excludeId) {
        if (paperPort.codeExists(paperCode, excludeId)) {
            throw new BizException(SurveyErrorCode.PAPER_CODE_DUPLICATED,
                    "问卷编码「" + paperCode + "」已存在");
        }
    }
}
