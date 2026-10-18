package com.lectureprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.discovery.DiscoveryProperties;
import com.lectureprocessor.editor.EditorProperties;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.support.SupportProperties;
import com.lectureprocessor.thumbnail.ThumbnailProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, SupportProperties.class, FileAgentProperties.class,
        MultilingualProperties.class, DiscoveryProperties.class, EditorProperties.class,
        ThumbnailProperties.class})
@EnableScheduling
public class LectureProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LectureProcessorApplication.class, args);
    }
}
