package com.nageoffer.ai.ragent.controller;

import com.nageoffer.ai.ragent.dto.kb.IntentNodeCreateReqDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeTreeRespDTO;
import com.nageoffer.ai.ragent.dto.kb.IntentNodeUpdateReqDTO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    @GetMapping("/intent-tree/trees")
    public Result<List<IntentNodeTreeRespDTO>> tree() {
        return Results.success(intentTreeService.getFullTree());
    }

    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateReqDTO requestParam) {
        return Results.success(intentTreeService.createNode(requestParam));
    }

    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable Long id, @RequestBody IntentNodeUpdateReqDTO requestParam) {
        intentTreeService.updateNode(id, requestParam);
    }

    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
    }
}
