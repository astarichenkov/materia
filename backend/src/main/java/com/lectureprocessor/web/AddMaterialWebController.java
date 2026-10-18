package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Entry point for adding material. Unlike the future import wizard, this page
 * hosts the single existing upload/URL forms (moved from the dashboard) so the
 * quick-link on the home page never duplicates download logic: it only carries
 * the URL here.
 */
@Controller
public class AddMaterialWebController {

    @GetMapping("/add")
    public String add(@RequestParam(name = "url", required = false) String url, Model model) {
        model.addAttribute("url", url == null ? "" : url);
        return "add";
    }
}
