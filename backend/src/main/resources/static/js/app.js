(function () {
  "use strict";

  var sidebar = document.getElementById("app-sidebar");
  var overlay = document.querySelector("[data-sidebar-close]");
  var toggle = document.querySelector("[data-sidebar-toggle]");
  var body = document.body;

  function isOpen() {
    return sidebar && sidebar.classList.contains("is-open");
  }

  function openDrawer() {
    if (!sidebar || !toggle) return;
    sidebar.classList.add("is-open");
    if (overlay) overlay.classList.add("is-visible");
    toggle.setAttribute("aria-expanded", "true");
    body.classList.add("drawer-open");
    var firstLink = sidebar.querySelector("a, summary, button");
    if (firstLink) firstLink.focus();
  }

  function closeDrawer(restoreFocus) {
    if (!sidebar || !toggle) return;
    sidebar.classList.remove("is-open");
    if (overlay) overlay.classList.remove("is-visible");
    toggle.setAttribute("aria-expanded", "false");
    body.classList.remove("drawer-open");
    if (restoreFocus) toggle.focus();
  }

  if (toggle) {
    toggle.addEventListener("click", function () {
      if (isOpen()) closeDrawer(true);
      else openDrawer();
    });
  }

  if (overlay) {
    overlay.addEventListener("click", function () { closeDrawer(true); });
  }

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && isOpen()) {
      closeDrawer(true);
    }
  });

  if (sidebar) {
    sidebar.addEventListener("click", function (event) {
      if (event.target.closest("a")) closeDrawer(false);
    });
  }

  window.addEventListener("resize", function () {
    if (window.innerWidth >= 1024 && isOpen()) closeDrawer(false);
  });

  // Remember collapsed/expanded state of navigation groups.
  try {
    var stored = JSON.parse(localStorage.getItem("lp.navGroups") || "{}");
    document.querySelectorAll("details.nav-group[data-group]").forEach(function (group) {
      var key = group.getAttribute("data-group");
      if (Object.prototype.hasOwnProperty.call(stored, key)) {
        group.open = Boolean(stored[key]);
      }
      group.addEventListener("toggle", function () {
        var state = JSON.parse(localStorage.getItem("lp.navGroups") || "{}");
        state[key] = group.open;
        localStorage.setItem("lp.navGroups", JSON.stringify(state));
      });
    });
  } catch (ignored) {
    // localStorage can be unavailable (private mode, strict policy).
  }

  // Give wide tables their own scroll container without changing table semantics.
  document.querySelectorAll(".content table").forEach(function (table) {
    if (table.closest(".table-scroll")) return;
    var wrapper = document.createElement("div");
    wrapper.className = "table-scroll";
    table.parentNode.insertBefore(wrapper, table);
    wrapper.appendChild(table);
  });

  // Destructive actions use a native confirm dialog; no clickable divs.
  document.addEventListener("click", function (event) {
    var trigger = event.target.closest("[data-confirm]");
    if (!trigger) return;
    var message = trigger.getAttribute("data-confirm") || "Подтвердить действие?";
    if (!window.confirm(message)) {
      event.preventDefault();
      event.stopPropagation();
    }
  });

  // A no-op re-run plan must not invite a pointless job: disable the submit
  // button and explain why. Re-enabled as soon as the plan needs work again.
  function syncRerunButton(container) {
    if (!container) return;
    var form = container.closest("form");
    if (!form) return;
    var button = form.querySelector("button[type=submit]");
    if (!button) return;
    var plan = container.querySelector("[data-plan-noop]");
    var noop = plan && plan.getAttribute("data-plan-noop") === "true";
    button.disabled = !!noop;
    button.setAttribute("aria-disabled", noop ? "true" : "false");
    var hint = form.querySelector("[data-rerun-noop-hint]");
    if (noop && !hint) {
      hint = document.createElement("span");
      hint.className = "muted";
      hint.setAttribute("data-rerun-noop-hint", "true");
      hint.textContent = " Всё уже готово — пересоздавать нечего.";
      button.insertAdjacentElement("afterend", hint);
    } else if (!noop && hint) {
      hint.remove();
    }
  }
  document.addEventListener("htmx:afterSwap", function (event) {
    syncRerunButton(event.detail ? event.detail.target : null);
  });
  document.addEventListener("DOMContentLoaded", function () {
    syncRerunButton(document.getElementById("rerun-plan"));
  });

  // HTMX errors should be visible and never replace the whole page silently.
  document.addEventListener("htmx:responseError", function (event) {
    var detail = event.detail || {};
    var target = detail.target;
    var status = detail.xhr ? detail.xhr.status : "";
    if (target && target.setAttribute && target.querySelector) {
      var existing = target.querySelector(".alert--danger[data-htmx-error]");
      if (existing) existing.remove();
      var node = document.createElement("div");
      node.className = "alert alert--danger";
      node.setAttribute("role", "alert");
      node.setAttribute("data-htmx-error", "true");
      node.textContent = status
        ? "Не удалось загрузить данные (HTTP " + status + "). Обновите страницу и повторите."
        : "Не удалось загрузить данные. Проверьте соединение и повторите.";
      target.prepend(node);
    }
  });
})();
