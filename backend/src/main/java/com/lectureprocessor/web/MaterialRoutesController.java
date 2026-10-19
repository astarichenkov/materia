package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Universal route aliases introduced with the Materia rebranding.
 *
 * <p>The legacy {@code /lectures/**} routes stay the canonical implementation:
 * bookmarks, API clients and persisted links keep working. These aliases only
 * redirect to them, so no second controller or duplicated logic exists.</p>
 */
@Controller
public class MaterialRoutesController {

    @GetMapping("/materials")
    public String materials() {
        return "redirect:/library";
    }

    @GetMapping("/materials/{id}")
    public String material(@PathVariable String id) {
        return "redirect:/lectures/" + id;
    }

    @GetMapping("/materials/{id}/editor")
    public String editor(@PathVariable String id) {
        return "redirect:/lectures/" + id + "/editor";
    }

    @GetMapping("/materials/{id}/chat")
    public String chat(@PathVariable String id) {
        return "redirect:/lectures/" + id + "/chat";
    }
}
