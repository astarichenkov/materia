package com.lectureprocessor.multilingual;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.util.FileChecksum;

/**
 * Resolves the terminology block injected into the translation prompt and
 * provides the glossary version used in checkpoint fingerprints.
 */
@Service
public class TranslationGlossaryService {

    /** Fallback used when a database has no seeded glossary rows yet. */
    static final List<GlossaryEntry> BUILTIN = List.of(
            builtin("embedding", "эмбеддинг"),
            builtin("embeddings", "эмбеддинги"),
            builtin("retrieval", "поиск релевантной информации"),
            builtin("fine-tuning", "дообучение"),
            builtin("prompt", "промпт"),
            builtin("token", "токен"),
            builtin("tokens", "токены"),
            builtin("pipeline", "конвейер"),
            builtin("checkpoint", "контрольная точка"),
            builtin("inference", "инференс"),
            builtin("latency", "задержка"),
            builtin("chunk", "чанк"),
            builtin("chunks", "чанки"));

    private final TranslationGlossaryRepository repository;
    private final MultilingualProperties properties;

    public TranslationGlossaryService(TranslationGlossaryRepository repository,
            MultilingualProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<GlossaryEntry> entries(String sourceLanguage, String targetLanguage) {
        List<GlossaryEntry> stored = repository.findEnabled(sourceLanguage, targetLanguage);
        return stored.isEmpty() ? BUILTIN : stored;
    }

    public List<GlossaryEntry> all() {
        return repository.findAll();
    }

    /** Content-derived version: changing any term invalidates translations. */
    public String version(String sourceLanguage, String targetLanguage) {
        List<GlossaryEntry> entries = entries(sourceLanguage, targetLanguage);
        String material = entries.stream()
                .map(e -> e.term() + "=" + e.translation())
                .reduce("", (a, b) -> a + "|" + b);
        return properties.glossaryVersion() + ":"
                + FileChecksum.sha256(material).substring(0, 16);
    }

    /** Human-readable glossary block for the prompt (bounded by the caller). */
    public String promptBlock(String sourceLanguage, String targetLanguage, int maxChars) {
        StringBuilder block = new StringBuilder();
        for (GlossaryEntry entry : entries(sourceLanguage, targetLanguage)) {
            String line = "- " + entry.term() + " -> " + entry.translation() + "\n";
            if (block.length() + line.length() > maxChars) {
                break;
            }
            block.append(line);
        }
        return block.toString();
    }

    public GlossaryEntry save(GlossaryEntry entry) {
        return repository.save(entry);
    }

    public boolean delete(long id) {
        return repository.delete(id);
    }

    public void setEnabled(long id, boolean enabled) {
        repository.setEnabled(id, enabled);
    }

    private static GlossaryEntry builtin(String term, String translation) {
        return new GlossaryEntry(0, term, translation, "en", "ru", false, true, true,
                java.time.Instant.EPOCH);
    }
}
