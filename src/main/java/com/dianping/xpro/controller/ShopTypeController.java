package com.dianping.xpro.controller;

import com.dianping.xpro.dto.Result;
import com.dianping.xpro.service.IShopTypeService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 前端控制器
 * </p>
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Autowired
    private IShopTypeService shopTypeService;
    
    @GetMapping("list")
    public Result queryTypeList() {
        return shopTypeService.queryTypeList();
    }
}
