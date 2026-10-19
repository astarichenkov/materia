/*
 * UX-4 import wizard — progressive enhancement only.
 *
 * Without JavaScript all three source panels stay visible and both existing
 * forms (POST /lectures, POST /lectures/from-url) submit normally. This file
 * adds the source switch, client-side pre-checks, the summary before submit and
 * double-submit protection. It never downloads or creates anything itself.
 */
(function () {
  "use strict";

  var wizard = document.querySelector("[data-import-wizard]");
  if (!wizard) return;

  var panels = Array.prototype.slice.call(wizard.querySelectorAll("[data-source-panel]"));
  var sourceRadios = Array.prototype.slice.call(wizard.querySelectorAll("[data-source-option]"));
  var fileInput = wizard.querySelector("[data-file-input]");
  var fileSelected = wizard.querySelector("[data-file-selected]");
  var fileError = wizard.querySelector("[data-file-error]");
  var allowedExtensions = fileInput
    ? (fileInput.getAttribute("accept") || "").split(",").map(function (value) {
      return value.trim().toLowerCase();
    }).filter(Boolean)
    : [];
  var maxBytes = fileInput ? Number(fileInput.getAttribute("data-max-bytes") || 0) : 0;
  var languageLabels = {
    AUTO: "авто (определить по речи)",
    ENGLISH: "английский",
    RUSSIAN: "русский"
  };
  var resultLabels = {
    TRANSLATE_TO_RUSSIAN: "перевод на русский",
    BILINGUAL: "двуязычный текст",
    ORIGINAL_ONLY: "только оригинал"
  };

  function selectedScenario(form) {
    return form ? form.querySelector('input[name="scenario"]:checked') : null;
  }

  function updateScenarioHint(form) {
    var selected = selectedScenario(form);
    var hint = form ? form.querySelector("[data-scenario-summary]") : null;
    if (!hint) return;
    if (!selected) {
      hint.textContent = "";
      return;
    }
    hint.textContent = selected.getAttribute("data-title") + ": "
      + selected.getAttribute("data-hint") + ". "
      + (selected.getAttribute("data-llm") === "true"
        ? "Используется выбранная модель."
        : "LLM не используется: документ собирается из транскрипции.");
  }

  function formatBytes(bytes) {
    if (!bytes || bytes < 0) return "0 Б";
    if (bytes >= 1024 * 1024 * 1024) {
      return (bytes / (1024 * 1024 * 1024)).toFixed(1).replace(".", ",") + " ГБ";
    }
    if (bytes >= 1024 * 1024) {
      return (bytes / (1024 * 1024)).toFixed(1).replace(".", ",") + " МБ";
    }
    if (bytes >= 1024) return Math.round(bytes / 1024) + " КБ";
    return bytes + " Б";
  }

  function extensionOf(name) {
    var index = name.lastIndexOf(".");
    return index >= 0 ? name.slice(index).toLowerCase() : "";
  }

  function formFor(source) {
    return wizard.querySelector('[data-source-form="' + source + '"]');
  }

  function selectValue(form, name) {
    var select = form.querySelector('select[name="' + name + '"]');
    return select ? select.value : "";
  }

  function checkboxLabel(input) {
    var explicit = input.getAttribute("data-summary-label");
    if (explicit) return explicit;
    var label = input.closest("label");
    return label ? label.textContent.trim() : input.name;
  }

  function updateSummary() {
    var source = wizard.getAttribute("data-source");
    var form = formFor(source);
    if (!form) return;
    var summary = form.querySelector("[data-import-summary]");
    var list = summary ? summary.querySelector("[data-summary-list]") : null;
    if (!summary || !list) return;

    var rows = [];
    if (source === "file") {
      var file = fileInput && fileInput.files && fileInput.files[0];
      if (!file) {
        summary.hidden = true;
        return;
      }
      rows.push(["Источник", "файл с компьютера"]);
      rows.push(["Файл", file.name + " (" + formatBytes(file.size) + ")"]);
    } else {
      var urlInput = form.querySelector('input[name="url"]');
      var value = urlInput ? urlInput.value.trim() : "";
      if (!value) {
        summary.hidden = true;
        return;
      }
      rows.push(["Источник", "видео по ссылке"]);
      rows.push(["Ссылка", value]);
      rows.push(["Качество", selectValue(form, "quality") || "по умолчанию"]);
    }
    var scenario = selectedScenario(form);
    if (scenario) {
      rows.push(["Сценарий", scenario.getAttribute("data-title")]);
    }
    rows.push(["Язык оригинала", languageLabels[selectValue(form, "languageMode")] || "авто"]);
    rows.push(["Перевод", resultLabels[selectValue(form, "translationMode")] || "по умолчанию"]);
    var extras = [];
    Array.prototype.slice.call(form.querySelectorAll('input[type="checkbox"]:checked'))
      .forEach(function (input) { extras.push(checkboxLabel(input)); });
    rows.push(["Дополнительно", extras.length ? extras.join(", ") : "не выбрано"]);
    rows.push(["Результат", scenario
      ? scenario.getAttribute("data-hint")
      : "транскрипт и документы Markdown/DOCX"]);
    rows.push(["Обработка", scenario && scenario.getAttribute("data-llm") === "false"
      ? "без LLM" : "LLM"]);

    list.textContent = "";
    rows.forEach(function (row) {
      var term = document.createElement("dt");
      term.textContent = row[0];
      var description = document.createElement("dd");
      description.textContent = row[1];
      list.appendChild(term);
      list.appendChild(description);
    });
    summary.hidden = false;
  }

  function validateFile() {
    if (!fileInput) return false;
    var file = fileInput.files && fileInput.files[0];
    if (!file) {
      if (fileSelected) fileSelected.hidden = true;
      if (fileError) {
        fileError.hidden = true;
        fileError.textContent = "";
      }
      updateSummary();
      return false;
    }
    if (fileSelected) {
      fileSelected.hidden = false;
      fileSelected.textContent = "Выбран файл: " + file.name + " (" + formatBytes(file.size) + ")";
    }
    var problem = "";
    if (maxBytes > 0 && file.size > maxBytes) {
      problem = "Файл больше допустимого размера (" + formatBytes(maxBytes) + "). Выберите файл меньше.";
    } else if (allowedExtensions.length && allowedExtensions.indexOf(extensionOf(file.name)) === -1) {
      problem = "Формат не поддерживается. Доступны: " + allowedExtensions.join(", ") + ".";
    }
    if (fileError) {
      fileError.hidden = !problem;
      fileError.textContent = problem;
    }
    // Audio-only lectures have no frames: keep the slides option honest.
    var audioExtensions = [".mp3", ".m4a", ".wav", ".flac"];
    var isAudio = audioExtensions.indexOf(extensionOf(file.name)) !== -1;
    var fileForm = formFor("file");
    var slides = fileForm ? fileForm.querySelector('input[name="slides"]') : null;
    if (slides) {
      slides.disabled = isAudio;
      if (isAudio) slides.checked = false;
    }
    var audioNote = wizard.querySelector("[data-audio-note]");
    if (audioNote) audioNote.hidden = !isAudio;
    updateSummary();
    return !problem;
  }

  function showSource(source, keepRadioFocus) {
    if (!source) return;
    wizard.setAttribute("data-source", source);
    panels.forEach(function (panel) {
      panel.hidden = panel.getAttribute("data-source-panel") !== source;
    });
    sourceRadios.forEach(function (radio) {
      radio.checked = radio.value === source;
    });
    updateSummary();
    // Keyboard arrow navigation must stay inside the radio group; hiding panels
    // can otherwise drop focus to the document body.
    if (keepRadioFocus) {
      sourceRadios.forEach(function (radio) {
        if (radio.value === source) radio.focus();
      });
    }
  }

  // Keyboard navigation inside the native radio group fires change, not click.
  wizard.addEventListener("change", function (event) {
    if (event.target && event.target.matches("[data-source-option]")) {
      showSource(event.target.value, true);
    }
    if (event.target && event.target.matches("[data-scenario-option]")) {
      var form = event.target.closest("form");
      var translation = form ? form.querySelector("[data-translation-mode]") : null;
      // Translation-only scenario needs translation enabled; the user can still
      // change it explicitly afterwards.
      if (event.target.value === "translation" && translation) {
        translation.value = "TRANSLATE_TO_RUSSIAN";
      }
      updateScenarioHint(form);
      updateSummary();
    }
  });

  document.addEventListener("click", function (event) {
    var explicit = event.target.closest("[data-source-select]");
    var radio = event.target.closest("[data-source-option]");
    if (!explicit && !radio) return;
    var source = explicit ? explicit.getAttribute("data-source-select") : radio.value;
    if (explicit) event.preventDefault();
    showSource(source, !explicit);
    // Only an explicit "switch to file" action moves focus into the panel; a
    // click on the radio itself must keep the radio group focusable.
    if (explicit) {
      var panel = wizard.querySelector('[data-source-panel="' + source + '"]');
      var focusable = panel ? panel.querySelector("input:not([type=hidden]), select, button, a") : null;
      if (focusable) focusable.focus();
    }
  });

  var dropzone = wizard.querySelector("[data-dropzone]");
  if (dropzone && fileInput) {
    ["dragenter", "dragover"].forEach(function (name) {
      dropzone.addEventListener(name, function (event) {
        event.preventDefault();
        dropzone.classList.add("is-dragover");
      });
    });
    ["dragleave", "dragend", "drop"].forEach(function (name) {
      dropzone.addEventListener(name, function (event) {
        if (name === "drop") event.preventDefault();
        dropzone.classList.remove("is-dragover");
      });
    });
    dropzone.addEventListener("drop", function (event) {
      var files = event.dataTransfer && event.dataTransfer.files;
      if (!files || !files.length) return;
      try {
        fileInput.files = files;
      } catch (ignored) {
        if (fileError) {
          fileError.hidden = false;
          fileError.textContent = "Перетаскивание недоступно в этом браузере — выберите файл кнопкой.";
        }
        return;
      }
      validateFile();
    });
  }

  if (fileInput) {
    fileInput.addEventListener("change", validateFile);
  }

  Array.prototype.slice.call(wizard.querySelectorAll("[data-import-form]")).forEach(function (form) {
    var source = form.getAttribute("data-source-form");
    form.addEventListener("input", updateSummary);
    form.addEventListener("change", updateSummary);
    form.addEventListener("submit", function (event) {
      if (form.getAttribute("data-submitting") === "true") {
        event.preventDefault();
        return;
      }
      if (source === "file" && !validateFile()) {
        event.preventDefault();
        if (fileInput) fileInput.focus();
        return;
      }
      if (source === "url") {
        var urlInput = form.querySelector('input[name="url"]');
        var value = urlInput ? urlInput.value.trim() : "";
        var urlError = form.querySelector("[data-url-error]");
        var problem = "";
        if (!value) {
          problem = "Вставьте ссылку на видео.";
        } else {
          try {
            var parsed = new URL(value);
            if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
              problem = "Ссылка должна начинаться с http:// или https://.";
            }
          } catch (ignored) {
            problem = "Это не похоже на ссылку. Пример: https://www.youtube.com/watch?v=...";
          }
        }
        if (problem) {
          event.preventDefault();
          if (urlError) {
            urlError.hidden = false;
            urlError.textContent = problem;
          }
          if (urlInput) urlInput.focus();
          return;
        }
        if (urlError) {
          urlError.hidden = true;
          urlError.textContent = "";
        }
      }
      // Last line of defence against a double click or a repeated submit.
      form.setAttribute("data-submitting", "true");
      var button = form.querySelector("[data-submit-button]");
      if (button) {
        button.disabled = true;
        button.textContent = source === "file" ? "Загружаю…" : "Добавляю…";
      }
      var hint = form.querySelector("[data-submit-hint]");
      if (hint) hint.textContent = "Отправляю запрос. Не закрывайте страницу.";
    });
  });

  // Returning with the back/forward cache must not leave the button disabled.
  window.addEventListener("pageshow", function (event) {
    if (!event.persisted) return;
    Array.prototype.slice.call(wizard.querySelectorAll("[data-import-form]")).forEach(function (form) {
      form.removeAttribute("data-submitting");
      var button = form.querySelector("[data-submit-button]");
      if (button) {
        button.disabled = false;
        button.textContent = "Добавить и начать обработку";
      }
    });
    if (fileInput) validateFile();
    Array.prototype.slice.call(wizard.querySelectorAll("[data-import-form]")).forEach(function (form) {
      updateScenarioHint(form);
    });
    updateSummary();
  });

  Array.prototype.slice.call(wizard.querySelectorAll("[data-import-form]")).forEach(function (form) {
    updateScenarioHint(form);
  });
  showSource(wizard.getAttribute("data-source") === "url" ? "url" : "file");
})();
