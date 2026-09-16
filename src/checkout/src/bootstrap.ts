// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import { ValidationPipe } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { SwaggerModule, DocumentBuilder } from '@nestjs/swagger';
import { AppModule } from './app.module';
import { CheckoutModule } from './checkout/checkout.module';

// main.ts에서 호출할 수 있도록 앱 시작 함수를 공개합니다.
export async function bootstrap(): Promise<void> {
  // AppModule을 기준으로 NestJS 애플리케이션을 만듭니다.
  const app = await NestFactory.create(AppModule);

  // 요청 데이터가 DTO에 선언된 검증 조건을 만족하는지 검사합니다.
  app.useGlobalPipes(new ValidationPipe());

  // 기존 Swagger API 문서 설정을 유지합니다.
  const config = new DocumentBuilder()
    .setTitle('Checkout service')
    .setDescription('The checkout API')
    .setVersion('1.0')
    .addTag('checkout')
    .addServer('http://localhost:8000')
    .build();

  const document = SwaggerModule.createDocument(app, config, {
    include: [CheckoutModule],
  });

  SwaggerModule.setup('api', app, document);

  // 종료 신호를 받으면 NestJS의 종료 처리 과정이 실행되도록 합니다.
  app.enableShutdownHooks();

  // PORT 환경변수가 있으면 그 값을, 없으면 8080을 사용합니다.
  const port = process.env.PORT || 8080;

  // 지정한 포트에서 HTTP 요청을 받기 시작합니다.
  await app.listen(port);
}