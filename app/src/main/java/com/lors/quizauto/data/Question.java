package com.lors.quizauto.data;

import com.google.gson.annotations.SerializedName;

public class Question {
    @SerializedName(value = "question", alternate = {"q", "text"})
    private String question;

    @SerializedName(value = "answer", alternate = {"a", "correct"})
    private String answer;

    public String getQuestion() { return question == null ? "" : question; }
    public String getAnswer()   { return answer   == null ? "" : answer;   }
}
