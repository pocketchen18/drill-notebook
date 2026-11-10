package com.drillnotebook.app.controller;

import com.drillnotebook.app.model.QuestionRecord;
import com.drillnotebook.app.repository.BankRepository;
import com.drillnotebook.app.repository.QuestionRepository;
import com.drillnotebook.app.service.PdfImportService;
import com.drillnotebook.app.service.QuestionImportService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MaterialSourceControllerTest {
    @Test void questionSourceLinkResolvesItsBankAndDeletedTargetReturns404() throws Exception {
        var questions = mock(QuestionRepository.class);
        var question = new QuestionRecord();
        question.id = 21; question.bankId = 7; question.stem = "目标题目"; question.answer = "A";
        when(questions.findById(21)).thenReturn(question);
        when(questions.findById(22)).thenThrow(new EmptyResultDataAccessException(1));
        var mvc = MockMvcBuilders.standaloneSetup(new BankController(mock(BankRepository.class), questions,
                mock(QuestionImportService.class), mock(PdfImportService.class))).build();
        mvc.perform(get("/api/questions/21")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21)).andExpect(jsonPath("$.bankId").value(7))
                .andExpect(jsonPath("$.stem").value("目标题目"));
        mvc.perform(get("/api/questions/22")).andExpect(status().isNotFound());
        verify(questions, never()).delete(anyLong());
    }
}
