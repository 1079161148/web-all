package com.webadmin.application.survey.port;

import com.webadmin.application.survey.command.SurveyReportCommand;
import com.webadmin.application.survey.dto.SurveyReportDTO;
import com.webadmin.application.survey.query.SurveyReportPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.Optional;

/** 调研报告读写端口。 */
public interface SurveyReportPort {

    PageResult<SurveyReportDTO> page(SurveyReportPageQuery query);

    Optional<SurveyReportDTO> findById(long id);

    Long insert(SurveyReportCommand command);

    void update(long id, SurveyReportCommand command);

    void delete(long id);
}
