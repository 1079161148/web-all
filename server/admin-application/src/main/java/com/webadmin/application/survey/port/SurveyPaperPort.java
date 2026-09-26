package com.webadmin.application.survey.port;

import com.webadmin.application.survey.command.SurveyPaperCommand;
import com.webadmin.application.survey.dto.SurveyPaperDTO;
import com.webadmin.application.survey.query.SurveyPaperPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.Optional;

/** 问卷 / 提纲读写端口。 */
public interface SurveyPaperPort {

    /** 分页（不返回富文本正文，正文走 {@link #findById}）。 */
    PageResult<SurveyPaperDTO> page(SurveyPaperPageQuery query);

    Optional<SurveyPaperDTO> findById(long id);

    boolean codeExists(String paperCode, Long excludeId);

    Long insert(SurveyPaperCommand command);

    void update(long id, SurveyPaperCommand command);

    void delete(long id);
}
