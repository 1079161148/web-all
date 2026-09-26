package com.webadmin.application.survey;

import com.webadmin.application.survey.command.SurveyCollectCommand;
import com.webadmin.application.survey.dto.SurveyCollectDTO;
import com.webadmin.application.survey.port.SurveyCollectPort;
import com.webadmin.application.survey.port.SurveyTaskPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据采集应用服务。
 *
 * <h3>为什么在这里补 taskName</h3>
 * 采集记录只持有 {@code taskId}（跨对象只持 ID 引用）。写入时把任务名<b>快照</b>下来，
 * 是为了让列表不必 JOIN、也不必再发一次请求取名字 —— 与 {@code iam_user}
 * 用 JOIN 取部门名的取舍不同：部门名要跟着改名实时变，而"这次采集属于哪次任务"
 * 是历史事实，改名后反而应保留当时的名字。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyCollectAppService {

    private final SurveyCollectPort collectPort;
    private final SurveyTaskPort taskPort;

    @Transactional
    public Long create(SurveyCollectCommand command) {
        String taskName = resolveTaskName(command.taskId());
        Long id = collectPort.insert(withTaskName(command, taskName));
        log.info("创建采集记录 id={} taskId={} channel={}", id, command.taskId(), command.channel());
        return id;
    }

    @Transactional
    public void update(long id, SurveyCollectCommand command) {
        collectPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.COLLECT_NOT_FOUND, "采集记录不存在"));
        collectPort.update(id, withTaskName(command, resolveTaskName(command.taskId())));
    }

    @Transactional
    public void delete(long id) {
        collectPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.COLLECT_NOT_FOUND, "采集记录不存在"));
        collectPort.delete(id);
    }

    public SurveyCollectDTO detail(long id) {
        return collectPort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.COLLECT_NOT_FOUND, "采集记录不存在"));
    }

    @Transactional
    public int batchCreate(List<SurveyCollectCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                create(commands.get(i));
            } catch (BizException e) {
                throw new BizException(SurveyErrorCode.COLLECT_NOT_FOUND,
                        "第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        log.info("批量导入采集记录 {} 条", commands.size());
        return commands.size();
    }

    private String resolveTaskName(Long taskId) {
        return taskPort.findById(taskId).map(dto -> dto.taskName())
                .orElseThrow(() -> new BizException(SurveyErrorCode.TASK_NOT_FOUND,
                        "所属调研任务不存在（taskId=" + taskId + "）"));
    }

    private SurveyCollectCommand withTaskName(SurveyCollectCommand command, String taskName) {
        return new SurveyCollectCommand(command.taskId(), taskName, command.channel(),
                command.collector(), command.collectDate(), command.sampleCount(),
                command.validCount(), command.qualityScore(), command.fileId(),
                command.fileName(), command.status(), command.remark());
    }
}
