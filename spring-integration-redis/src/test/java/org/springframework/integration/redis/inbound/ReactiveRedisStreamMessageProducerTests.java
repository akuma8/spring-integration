/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.redis.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.stream.StreamReceiver;
import org.springframework.integration.channel.FluxMessageChannel;
import org.springframework.integration.handler.ReactiveMessageHandlerAdapter;
import org.springframework.integration.redis.outbound.ReactiveRedisStreamMessageHandler;
import org.springframework.integration.redis.rules.RedisAvailable;
import org.springframework.integration.redis.rules.RedisAvailableRule;
import org.springframework.integration.redis.rules.RedisAvailableTests;
import org.springframework.integration.redis.util.Address;
import org.springframework.integration.redis.util.Person;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * @author Attoumane Ahamadi
 * @author Artem Bilan
 *
 * @since 5.4
 */
@RunWith(SpringRunner.class)
@DirtiesContext
public class ReactiveRedisStreamMessageProducerTests extends RedisAvailableTests {

	private static final String STREAM_KEY = "myStream";

	private static final String CONSUMER = "consumer";

	@Autowired
	FluxMessageChannel fluxMessageChannel;

	@Autowired
	ReactiveRedisStreamMessageProducer redisStreamMessageProducer;

	@Autowired
	ReactiveRedisTemplate<String, ?> template;

	@Autowired
	ReactiveMessageHandlerAdapter messageHandler;

	@Before
	public void delKey() {
		this.template.delete(STREAM_KEY).block();
	}

	@After
	public void tearDown() {
		this.redisStreamMessageProducer.stop();
	}

	@Test
	@RedisAvailable
	public void testConsumerGroupCreation() {
		this.redisStreamMessageProducer.setCreateConsumerGroup(true);
		this.redisStreamMessageProducer.setConsumerName(CONSUMER);
		this.redisStreamMessageProducer.afterPropertiesSet();
		this.redisStreamMessageProducer.start();

		Flux.from(this.fluxMessageChannel).subscribe();

		this.template.opsForStream()
				.groups(STREAM_KEY)
				.next()
				.as(StepVerifier::create)
				.assertNext((infoGroup) ->
						assertThat(infoGroup.groupName()).isEqualTo(this.redisStreamMessageProducer.getBeanName()))
				.thenCancel()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	@RedisAvailable
	public void testReadingMessageAsStandaloneClient() {
		Address address = new Address("Rennes 3, France");
		Person person = new Person(address, "Attoumane");
		this.messageHandler.handleMessage(new GenericMessage<>(person));

		this.redisStreamMessageProducer.setCreateConsumerGroup(false);
		this.redisStreamMessageProducer.setConsumerName(null);
		this.redisStreamMessageProducer.setReadOffset(ReadOffset.from("0"));
		this.redisStreamMessageProducer.afterPropertiesSet();
		this.redisStreamMessageProducer.start();

		Flux.from(this.fluxMessageChannel)
				.as(StepVerifier::create)
				.assertNext(message -> assertThat(message.getPayload()).isEqualTo(person))
				.thenCancel()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	@RedisAvailable
	public void testReadingMessageAsConsumerInConsumerGroup() {
		//TODO find why the test above does not execute before implementing this one
	}

	@Configuration
	static class ContextConfig {

		@Bean
		ReactiveRedisStreamMessageHandler redisStreamMessageHandler() {
			return new ReactiveRedisStreamMessageHandler(RedisAvailableRule.connectionFactory, STREAM_KEY);
		}

		@Bean
		public ReactiveMessageHandlerAdapter reactiveMessageHandlerAdapter() {
			return new ReactiveMessageHandlerAdapter(redisStreamMessageHandler());
		}

		@Bean
		ReactiveRedisTemplate<String, ?> reactiveStreamOperations() {
			return new ReactiveRedisTemplate<>(RedisAvailableRule.connectionFactory,
					RedisSerializationContext.string());
		}

		@Bean
		FluxMessageChannel fluxMessageChannel() {
			return new FluxMessageChannel();
		}

		@Bean
		ReactiveRedisStreamMessageProducer reactiveRedisStreamProducer() {
			ReactiveRedisStreamMessageProducer messageProducer =
					new ReactiveRedisStreamMessageProducer(RedisAvailableRule.connectionFactory, STREAM_KEY);
			messageProducer.setStreamReceiverOptions(
					StreamReceiver.StreamReceiverOptions.builder()
							.pollTimeout(Duration.ofMillis(100))
							.targetType(Person.class)
							.build());
			messageProducer.setAutoStartup(false);
			messageProducer.setOutputChannel(fluxMessageChannel());
			return messageProducer;
		}

	}

}
