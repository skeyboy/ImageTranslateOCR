(() => {
  const root = document.querySelector("#record-detail");
  if (!root) return;

  const captureToggle = document.querySelector("#rendered-capture-toggle");
  const sourceCapture = document.querySelector("#source-capture-view");
  const renderedCapture = document.querySelector("#rendered-capture-view");
  const sourceCaptureMeta = document.querySelector("#source-capture-meta");
  const renderedCaptureMeta = document.querySelector("#rendered-capture-meta");
  const captureTitle = document.querySelector("#capture-view-title");
  const updateCaptureView = () => {
    const showRendered = Boolean(captureToggle?.checked && !captureToggle.disabled);
    if (sourceCapture) sourceCapture.hidden = showRendered;
    if (renderedCapture) renderedCapture.hidden = !showRendered;
    if (sourceCaptureMeta) sourceCaptureMeta.hidden = showRendered;
    if (renderedCaptureMeta) renderedCaptureMeta.hidden = !showRendered;
    if (captureTitle) {
      const renderedLabel = captureToggle?.nextElementSibling?.textContent || "端侧实际回贴";
      captureTitle.textContent = showRendered
        ? renderedLabel.replace(/^显示/u, "")
        : "翻译前 OCR 采集图";
    }
  };
  captureToggle?.addEventListener("change", updateCaptureView);
  updateCaptureView();

  const fallbackCopy = (text) => {
    const input = document.createElement("textarea");
    input.value = text;
    input.setAttribute("readonly", "");
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.append(input);
    input.select();
    const copied = document.execCommand("copy");
    input.remove();
    if (!copied) throw new Error("copy command was rejected");
  };

  const copyText = async (text) => {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return;
      } catch (_) {
        // LAN HTTP pages may not expose the secure Clipboard API.
      }
    }
    fallbackCopy(text);
  };

  const reportCopy = (button, success, originalLabel) => {
    button.textContent = success ? "已复制" : "复制失败";
    button.classList.toggle("copy-button-error", !success);
    window.setTimeout(() => {
      button.textContent = originalLabel;
      button.classList.remove("copy-button-error");
    }, 1600);
  };

  const providerRequestBody = (pre) => {
    const raw = pre?.textContent?.trim() || "";
    const parsed = JSON.parse(raw);
    const body = parsed?.request && typeof parsed.request === "object" ? parsed.request : parsed;
    if (body && typeof body === "object") delete body._timings;
    return body;
  };

  document.querySelectorAll("[data-copy-target]").forEach((button) => {
    button.addEventListener("click", async () => {
      const originalLabel = button.textContent;
      const text = document.getElementById(button.dataset.copyTarget)?.textContent || "";
      try {
        await copyText(text);
        reportCopy(button, true, originalLabel);
      } catch (_) {
        reportCopy(button, false, originalLabel);
      }
    });
  });

  document.querySelectorAll("[data-copy-provider-request]").forEach((button) => {
    button.addEventListener("click", async () => {
      const originalLabel = button.textContent;
      try {
        const pre = document.getElementById(button.dataset.copyProviderRequest);
        await copyText(JSON.stringify(providerRequestBody(pre), null, 2));
        reportCopy(button, true, originalLabel);
      } catch (_) {
        reportCopy(button, false, originalLabel);
      }
    });
  });

  document.querySelectorAll("[data-copy-curl]").forEach((button) => {
    button.addEventListener("click", async () => {
      const originalLabel = button.textContent;
      try {
        const pre = document.getElementById(button.dataset.copyCurl);
        const body = JSON.stringify(providerRequestBody(pre), null, 2);
        const endpoint = String(button.dataset.endpoint || "").replaceAll("'", "'\"'\"'");
        const apiKeyEnv = button.dataset.apiKeyEnv;
        const authValue = `\${${apiKeyEnv}}`;
        const authHeader = button.dataset.requiresAuth === "true"
          ? `  -H "Authorization: Bearer ${authValue}" \\\n`
          : "";
        const command = `curl -X POST '${endpoint}' \\\n${authHeader}  -H 'Content-Type: application/json' \\\n  --data-binary @- <<'JSON'\n${body}\nJSON`;
        await copyText(command);
        reportCopy(button, true, originalLabel);
      } catch (_) {
        reportCopy(button, false, originalLabel);
      }
    });
  });

  const decode = (value) => {
    if (!value) return null;
    const bytes = Uint8Array.from(atob(value), (char) => char.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  };

  const request = decode(root.dataset.requestJson);
  const response = decode(root.dataset.responseJson);
  if (!request) return;

  const viewport = request.viewport;
  const responseByGroup = new Map((response?.results ?? []).map((result) => [result.groupId, result]));
  const groupsById = new Map(request.groups.map((group) => [group.groupId, group]));
  const regionsById = new Map(request.regions.map((region) => [region.regionId, region]));
  const regionsByGroup = new Map();
  request.regions.forEach((region) => {
    if (!regionsByGroup.has(region.groupId)) regionsByGroup.set(region.groupId, []);
    regionsByGroup.get(region.groupId).push(region);
  });

  const roleClass = (role) => `role-${String(role || "body").toLowerCase()}`;
  const place = (element, bounds) => {
    element.style.left = `${bounds.left / viewport.width * 100}%`;
    element.style.top = `${bounds.top / viewport.height * 100}%`;
    element.style.width = `${(bounds.right - bounds.left) / viewport.width * 100}%`;
    element.style.height = `${(bounds.bottom - bounds.top) / viewport.height * 100}%`;
  };

  const setupCanvas = (element) => {
    element.style.setProperty("--viewport-ratio", `${viewport.width} / ${viewport.height}`);
    element.setAttribute("aria-label", `${viewport.width} × ${viewport.height}`);
  };

  const planSlotTexts = (group, slots) => {
    const lines = String(group.sourceText || "")
      .split(/\n+/u)
      .map((line) => line.trim())
      .filter(Boolean);
    if (!lines.length) return slots.map(() => "");
    if (slots.length === 1) return [lines.join("\n")];
    let cursor = 0;
    return slots.map((slot, index) => {
      if (index === slots.length - 1) return lines.slice(cursor).join("\n");
      const remainingSlots = slots.length - index;
      const remainingLines = lines.length - cursor;
      const remainingHeight = slots.slice(index).reduce(
        (sum, item) => sum + Math.max(1, item.bottom - item.top),
        0
      );
      const proportional = Math.round(
        remainingLines * Math.max(1, slot.bottom - slot.top) / remainingHeight
      );
      const count = Math.max(1, Math.min(
        proportional,
        remainingLines - (remainingSlots - 1)
      ));
      const text = lines.slice(cursor, cursor + count).join("\n");
      cursor += count;
      return text;
    });
  };

  const sourceCanvas = document.querySelector("#source-layout");
  const serverCanvas = document.querySelector("#server-layout");
  const translationCanvas = document.querySelector("#translation-layout");
  const serverPlanEntries = [];
  setupCanvas(sourceCanvas);
  setupCanvas(serverCanvas);
  setupCanvas(translationCanvas);

  request.groups.forEach((group) => {
    const outline = document.createElement("div");
    outline.className = `group-outline ${roleClass(group.role)}`;
    place(outline, group.bounds);
    sourceCanvas.append(outline);
  });
  request.regions.forEach((region) => {
    const group = groupsById.get(region.groupId);
    const box = document.createElement("div");
    box.className = `ocr-region ${roleClass(group?.role)}`;
    const text = document.createElement("span");
    text.className = "box-text";
    text.textContent = region.rawText || region.text;
    box.append(text);
    place(box, region.bounds);
    sourceCanvas.append(box);
  });

  const documentPlan = response?.documentPlan;
  if (!documentPlan?.groups?.length) {
    const empty = document.createElement("p");
    empty.className = "plan-empty";
    empty.textContent = "旧记录无服务端语义计划";
    serverCanvas.append(empty);
  } else {
    documentPlan.groups.forEach((group) => {
      const outline = document.createElement("div");
      outline.className = `group-outline ${roleClass(group.role)}`;
      place(outline, group.bounds);
      serverCanvas.append(outline);
      const slots = group.renderSlots?.length ? group.renderSlots : [group.bounds];
      const slotTexts = planSlotTexts(group, slots);
      const textElements = [];
      slots.forEach((slot, index) => {
        const element = document.createElement("div");
        element.className = `render-slot ${roleClass(group.role)}`;
        place(element, slot);
        const text = document.createElement("span");
        text.className = "plan-text";
        text.textContent = slotTexts[index];
        element.append(text);
        textElements.push(text);
        element.title = `${group.groupId}\n${group.sourceText || ""}`;
        serverCanvas.append(element);
      });
      const label = document.createElement("span");
      label.className = "plan-label";
      if (group.bounds.top / viewport.height < 0.025) label.classList.add("plan-label-inside");
      const confidence = Math.round((group.groupingConfidence ?? 0) * 100);
      label.textContent = `${documentPlan.mode} · ${group.sourceGroupIds?.length ?? 1}组 · ${confidence}%${group.authoritativeEligible ? " · 可执行" : ""}`;
      label.title = `${group.groupId}\n${group.sourceText || ""}`;
      label.style.left = `${group.bounds.left / viewport.width * 100}%`;
      label.style.top = `${group.bounds.top / viewport.height * 100}%`;
      serverCanvas.append(label);
      serverPlanEntries.push({ group, textElements });
    });
  }

  const activeTranslationGroups = documentPlan?.mode === "AUTHORITATIVE"
    ? documentPlan.groups
    : request.groups;
  const translationGroups = activeTranslationGroups.map((group) => {
    const result = responseByGroup.get(group.groupId);
    const slots = result?.layoutHint?.renderSlots?.length
      ? result.layoutHint.renderSlots
      : group.renderSlots?.length ? group.renderSlots : [group.bounds];
    const text = result?.translatedText || group.sourceText;
    const sourceCoverSlots = result?.layoutHint?.sourceCoverSlots?.length
      ? result.layoutHint.sourceCoverSlots
      : (group.memberRegionIds ?? [])
        .map((id) => regionsById.get(id))
        .filter(Boolean)
        .flatMap((region) => region.componentBounds?.length
          ? region.componentBounds
          : [region.bounds]);
    const outline = document.createElement("div");
    outline.className = `group-outline ${roleClass(group.role)}`;
    place(outline, group.bounds);
    translationCanvas.append(outline);
    sourceCoverSlots.forEach((slot) => {
      const cover = document.createElement("div");
      cover.className = "source-cover-slot";
      place(cover, slot);
      translationCanvas.append(cover);
    });
    const slotElements = slots.map((slot) => {
      const element = document.createElement("div");
      element.className = `render-slot ${roleClass(group.role)}`;
      place(element, slot);
      translationCanvas.append(element);
      return element;
    });
    return {
      group,
      result,
      slots,
      sourceCoverSlots,
      slotElements,
      text: text.replace(/\s+/g, " ").trim()
    };
  });

  const boundaryEnd = (text, requested) => {
    const end = Math.max(1, Math.min(requested, text.length));
    if (end === text.length || !/[\p{L}\p{N}]/u.test(text[end - 1]) || !/[\p{L}\p{N}]/u.test(text[end])) return end;
    const minimum = Math.max(1, end - 24);
    for (let index = end; index >= minimum; index -= 1) {
      if (/\s|[,.;:，。；：!?！？]/u.test(text[index - 1])) return index;
    }
    return end;
  };

  const fits = (element) => element.scrollHeight <= element.clientHeight + 1 && element.scrollWidth <= element.clientWidth + 1;
  const medianSourceLineHeight = (group) => {
    const heights = (group.memberRegionIds ?? [])
      .map((id) => regionsById.get(id))
      .filter(Boolean)
      .map((region) => region.bounds.bottom - region.bounds.top)
      .filter((height) => height > 0)
      .sort((a, b) => a - b);
    if (heights.length) return heights[Math.floor(heights.length / 2)];
    return Math.max(1, (group.bounds.bottom - group.bounds.top) / Math.max(1, group.sourceLineCount ?? 1));
  };

  const renderPlanEntry = (entry) => {
    const scale = serverCanvas.clientWidth / viewport.width;
    const preferred = Math.max(6, medianSourceLineHeight(entry.group) * 0.72 * scale);
    const applySize = (size) => {
      entry.textElements.forEach((text) => {
        text.style.fontSize = `${size}px`;
        text.style.lineHeight = "1.08";
      });
    };
    applySize(preferred);
    if (entry.textElements.every(fits)) return;
    let low = Math.max(6, preferred * 0.68);
    let high = preferred;
    let best = low;
    for (let index = 0; index < 10; index += 1) {
      const size = (low + high) / 2;
      applySize(size);
      if (entry.textElements.every(fits)) {
        best = size;
        low = size;
      } else {
        high = size;
      }
    }
    applySize(best);
  };

  const fitPrefix = (element, text) => {
    element.textContent = text;
    if (fits(element)) return { text, consumed: text.length };
    let low = 1;
    let high = text.length - 1;
    let best = 0;
    while (low <= high) {
      const midpoint = Math.floor((low + high) / 2);
      element.textContent = text.slice(0, midpoint);
      if (fits(element)) {
        best = midpoint;
        low = midpoint + 1;
      } else {
        high = midpoint - 1;
      }
    }
    if (!best) return null;
    const consumed = boundaryEnd(text, best);
    const fittedText = text.slice(0, consumed).trimEnd();
    element.textContent = fittedText;
    return { text: fittedText, consumed };
  };

  const applyFlow = (entry, sourceFontSize, spacing) => {
    let cursor = 0;
    const texts = [];
    const scale = translationCanvas.clientWidth / viewport.width;
    const fontSize = Math.max(6, sourceFontSize * scale);
    entry.slotElements.forEach((element) => {
      element.textContent = "";
      element.style.fontSize = `${fontSize}px`;
      element.style.lineHeight = String(1.18 * spacing);
    });
    for (const element of entry.slotElements) {
      if (cursor >= entry.text.length) break;
      while (cursor < entry.text.length && /\s/u.test(entry.text[cursor])) cursor += 1;
      const fitted = fitPrefix(element, entry.text.slice(cursor));
      if (!fitted) break;
      texts.push(fitted.text);
      cursor += fitted.consumed;
    }
    return { complete: cursor >= entry.text.length, texts, fontSize };
  };

  const renderEntry = (entry) => {
    const regions = entry.group.memberRegionIds?.map((id) => regionsById.get(id)).filter(Boolean)
      ?? regionsByGroup.get(entry.group.groupId) ?? [];
    const heights = regions.map((region) => region.bounds.bottom - region.bounds.top).sort((a, b) => a - b);
    const sourceLineHeight = heights[Math.floor(heights.length / 2)] || (entry.group.bounds.bottom - entry.group.bounds.top);
    const preferred = Math.max(7, sourceLineHeight * 0.78);
    const minimum = Math.max(6, preferred * Math.max(0.68, entry.result?.layoutHint?.minimumTextScale ?? 0.68));
    let selected = null;
    let selectedSpacing = 1;
    for (const spacing of [1, 0.92, 0.86]) {
      let low = minimum;
      let high = preferred;
      let best = null;
      for (let index = 0; index < 8; index += 1) {
        const size = (low + high) / 2;
        const candidate = applyFlow(entry, size, spacing);
        if (candidate.complete) {
          best = { ...candidate, sourceFontSize: size };
          low = size;
        } else {
          high = size;
        }
      }
      if (best) {
        selected = best;
        selectedSpacing = spacing;
        break;
      }
    }

    let outcome = "FULL";
    if (selected) {
      applyFlow(entry, selected.sourceFontSize, selectedSpacing);
      if (selectedSpacing !== 1 || selected.sourceFontSize < preferred * 0.98) outcome = "COMPACT";
    } else {
      outcome = "MORE";
      const partial = applyFlow(entry, minimum, 0.86);
      const lastIndex = Math.max(0, partial.texts.length - 1);
      const last = entry.slotElements[lastIndex];
      if (last) {
        let value = `${last.textContent.trimEnd()}… 更多`;
        last.textContent = value;
        while (!fits(last) && value.length > 5) {
          value = `${value.slice(0, -5).trimEnd()}… 更多`;
          last.textContent = value;
        }
      }
    }
    const badge = document.createElement("span");
    badge.className = "layout-outcome";
    badge.textContent = outcome;
    entry.slotElements[0]?.append(badge);
    return outcome;
  };

  const renderAll = () => {
    sourceCanvas.querySelectorAll(".ocr-region").forEach((box, index) => {
      const region = request.regions[index];
      const scale = sourceCanvas.clientWidth / viewport.width;
      const height = region.bounds.bottom - region.bounds.top;
      box.style.fontSize = `${Math.max(6, height * 0.72 * scale)}px`;
    });
    serverPlanEntries.forEach(renderPlanEntry);
    translationGroups.forEach((entry) => {
      entry.slotElements.forEach((element) => { element.replaceChildren(); });
    });
    const outcomes = translationGroups.map(renderEntry);
    const counts = outcomes.reduce((result, outcome) => {
      result[outcome] = (result[outcome] || 0) + 1;
      return result;
    }, {});
    const planSummary = documentPlan
      ? `${documentPlan.mode} ${documentPlan.metrics?.clientGroupCount ?? 0}→${documentPlan.metrics?.plannedGroupCount ?? 0}组`
      : "无服务端计划";
    document.querySelector("#layout-summary").textContent = `${viewport.width} × ${viewport.height} · ${planSummary} · ${Object.entries(counts).map(([key, count]) => `${key} ${count}`).join(" · ")}`;
  };

  let frame = 0;
  const schedule = () => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(renderAll);
  };
  const resizeObserver = new ResizeObserver(schedule);
  [sourceCanvas, serverCanvas, translationCanvas].forEach((canvas) => resizeObserver.observe(canvas));
  schedule();
})();
