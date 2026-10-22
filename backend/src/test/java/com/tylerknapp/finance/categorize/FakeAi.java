package com.tylerknapp.finance.categorize;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Scriptable stand-in for Claude. */
public class FakeAi implements AiCategorizer {
    public boolean enabled = false;
    /** Answers for a batch; may return anything, including garbage. */
    public Function<List<Input>, List<Answer>> respond = batch -> { throw new IllegalStateException("no script"); };
    public final List<List<Input>> batches = new ArrayList<>();
    public final List<List<Example>> examplesSeen = new ArrayList<>();
    public Taxonomy taxonomySeen;

    public void reset() {
        enabled = false;
        respond = batch -> { throw new IllegalStateException("no script"); };
        batches.clear();
        examplesSeen.clear();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<Answer> categorize(List<Input> batch, List<Example> examples, Taxonomy taxonomy) {
        batches.add(List.copyOf(batch));
        examplesSeen.add(List.copyOf(examples));
        taxonomySeen = taxonomy;
        return respond.apply(batch);
    }
}
