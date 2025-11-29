package com.nageoffer.ai.ragent.controller;

import com.nageoffer.ai.ragent.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.controller.request.IntentNodeUpdateRequest;
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
    public Result<List<IntentNodeTreeVO>> tree() {
        return Results.success(intentTreeService.getFullTree());
    }

    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        return Results.success(intentTreeService.createNode(requestParam));
    }

    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable Long id, @RequestBody IntentNodeUpdateRequest requestParam) {
        intentTreeService.updateNode(id, requestParam);
    }

    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
    }
}
