package com.kepler.mock.impl;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.kepler.extension.Extension;
import com.kepler.mock.Mocker;
import com.kepler.mock.MockerContext;
import com.kepler.service.Service;

/**
 * MockerContext链
 * 
 * @author longyaokun
 * @date 2016年8月17日
 */
public class ChainedMockerContext implements Extension, MockerContext {

	private List<MockerContext> mockerContexts;

	@Override
	public Extension install(Object instance) {
		MockerContext mockerContext = MockerContext.class.cast(instance);
		this.mockerContexts.add(mockerContext);
		Collections.sort(this.mockerContexts, new Comparator<MockerContext>() {
			@Override
			public int compare(MockerContext o1, MockerContext o2) {
				return o1.getOrder() - o2.getOrder();
			}
		});
		return this;
	}

	@Override
	public Class<?> interested() {
		return MockerContext.class;
	}

	@Override
	public int getOrder() {
		return Integer.MAX_VALUE;
	}

	@Override
	public Mocker get(Service service) {
		for(MockerContext mockerContext : this.mockerContexts) {
			Mocker mocker = mockerContext.get(service);
			if(mocker != null) {
				return mocker;
			}
		}
		return null;
	}

}
