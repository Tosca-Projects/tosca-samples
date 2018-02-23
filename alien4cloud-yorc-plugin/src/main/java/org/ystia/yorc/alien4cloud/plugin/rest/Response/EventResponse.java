package org.ystia.yorc.alien4cloud.plugin.rest.Response;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@ToString
@Getter
@Setter
public class EventResponse {

    private List<Event> events;
    private int last_index;
}