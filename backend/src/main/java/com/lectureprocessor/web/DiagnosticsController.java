package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.lectureprocessor.diagnostics.DiagnosticsService;

/** Installation diagnostics page and JSON endpoint. */
@Controller
public class DiagnosticsController {

    private final DiagnosticsService diagnostics;

    public DiagnosticsController(DiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/diagnostics")
    public String page(Model model) {
        model.addAttribute("report", diagnostics.report());
        return "diagnostics";
    }

    @GetMapping("/api/diagnostics")
    @ResponseBody
    public DiagnosticsService.DiagnosticsReport api() {
        return diagnostics.report();
    }
}
