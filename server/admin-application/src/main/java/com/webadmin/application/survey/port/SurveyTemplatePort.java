package com.webadmin.application.survey.port;

import com.webadmin.application.survey.command.SurveyTemplateCommand;
import com.webadmin.application.survey.dto.SurveyTemplateDTO;
import com.webadmin.application.survey.query.SurveyTemplatePageQuery;
import com.webadmin.common.api.PageResult;
import java.util.Optional;

/** 调研模板读写端口。 */
public interface SurveyTemplatePort {

    PageResult<SurveyTemplateDTO> page(SurveyTemplatePageQuery query);

    Optional<SurveyTemplateDTO> findById(long id);

    boolean codeExists(String templateCode, Long excludeId);

    Long insert(SurveyTemplateCommand command);

    void update(long id, SurveyTemplateCommand command);

    void delete(long id);

    /** 引用次数 +1（套用模板时调用）。 */
    void increaseUsage(long id);
}
