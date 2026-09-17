package com.dianping.xpro;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.dianping.xpro.mapper")
@SpringBootApplication
public class DianpingXproApplication {

    public static void main(String[] args) {
        SpringApplication.run(DianpingXproApplication.class, args);
    }
}
