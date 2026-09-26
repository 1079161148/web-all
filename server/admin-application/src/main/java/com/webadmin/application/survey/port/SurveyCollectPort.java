package com.webadmin.application.survey.port;

import com.webadmin.application.survey.command.SurveyCollectCommand;
import com.webadmin.application.survey.dto.SurveyCollectDTO;
import com.webadmin.application.survey.query.SurveyCollectPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.Optional;

/** 数据采集读写端口。 */
public interface SurveyCollectPort {

    PageResult<SurveyCollectDTO> page(SurveyCollectPageQuery query);

    Optional<SurveyCollectDTO> findById(long id);

    Long insert(SurveyCollectCommand command);

    void update(long id, SurveyCollectCommand command);

    void delete(long id);
}
