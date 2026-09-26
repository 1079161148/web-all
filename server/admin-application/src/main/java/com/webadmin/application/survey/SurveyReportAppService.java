package com.webadmin.application.survey;

import com.webadmin.application.survey.command.SurveyReportCommand;
import com.webadmin.application.survey.dto.SurveyReportDTO;
import com.webadmin.application.survey.port.SurveyReportPort;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调研分析报告应用服务（taskName 快照的理由同 {@link SurveyCollectAppService}）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyReportAppService {

    private final SurveyReportPort reportPort;
    private final SurveyTaskPort taskPort;

    @Transactional
    public Long create(SurveyReportCommand command) {
        Long id = reportPort.insert(withTaskName(command, resolveTaskName(command.taskId())));
        log.info("创建调研报告 id={} taskId={} type={}", id, command.taskId(), command.reportType());
        return id;
    }

    @Transactional
    public void update(long id, SurveyReportCommand command) {
        reportPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.REPORT_NOT_FOUND, "报告不存在"));
        reportPort.update(id, withTaskName(command, resolveTaskName(command.taskId())));
    }

    @Transactional
    public void delete(long id) {
        reportPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.REPORT_NOT_FOUND, "报告不存在"));
        reportPort.delete(id);
    }

    public SurveyReportDTO detail(long id) {
        return reportPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.REPORT_NOT_FOUND, "报告不存在"));
    }

    @Transactional
    public int batchCreate(List<SurveyReportCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                create(commands.get(i));
            } catch (BizException e) {
                throw new BizException(SurveyErrorCode.REPORT_NOT_FOUND,
                        "第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return commands.size();
    }

    private String resolveTaskName(Long taskId) {
        return taskPort.findById(taskId).map(dto -> dto.taskName())
                .orElseThrow(() -> new BizException(SurveyErrorCode.TASK_NOT_FOUND,
                        "所属调研任务不存在（taskId=" + taskId + "）"));
    }

    private SurveyReportCommand withTaskName(SurveyReportCommand command, String taskName) {
        return new SurveyReportCommand(command.taskId(), taskName, command.reportTitle(),
                command.reportType(), command.author(), command.publishDate(), command.summary(),
                command.content(), command.fileId(), command.fileName(), command.status(),
                command.remark());
    }
}
