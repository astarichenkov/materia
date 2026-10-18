package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entry point for adding material. UX-2 intentionally keeps this page as a thin
 * bridge to the existing upload/URL/discovery flows; the full import wizard is a
 * later UX stage and is not reimplemented here.
 */
@Controller
public class AddMaterialWebController {

    @GetMapping("/add")
    public String add() {
        return "add";
    }
}
