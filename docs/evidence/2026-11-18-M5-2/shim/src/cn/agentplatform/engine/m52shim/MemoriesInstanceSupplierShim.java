package cn.agentplatform.engine.m52shim;

import cn.agentplatform.engine.application.Memories;
import cn.agentplatform.engine.infra.MemoryRepository;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.InstanceSupplier;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.stereotype.Component;

/**
 * M5-2 真实链路演示起栈外挂（docs/evidence，不属于服务源码；机制同 wt-m53 scripts/e2e 缺陷登记①——Memories 双构造器阻塞 engine 装配，BeanFactoryPostProcessor 挂确定性实例供给器补装配，不改服务源码）。
 *
 * <p>现状（诚实登记）：engine-runtime 的 {@code Memories}（@Service）自 734fe1a7（A3-4）起有
 * 两个 public 构造器且均无 @Autowired——Spring Boot 全上下文构造时无法选定构造器，启动即
 * 失败（No default constructor found）。该阻塞与 M5-3 无关且按卡面约束不得改服务源码，故本
 * shim 以 BeanFactoryPostProcessor 给组件扫描出的 {@code memories} 定义挂确定性实例供给器
 * （等价于给带 RuntimeClients 的 3 参构造器标 @Autowired），仅补足装配、不改任何业务语义。
 * 阻塞修复合入服务源码后，本 shim 行为与 @Autowired 装配一致，可整体下线。</p>
 */
@Component
public class MemoriesInstanceSupplierShim implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        if (!beanFactory.containsBeanDefinition("memories")) return;
        BeanDefinition definition = beanFactory.getBeanDefinition("memories");
        if (definition instanceof AbstractBeanDefinition abstractDefinition) {
            abstractDefinition.setInstanceSupplier(
                    new InstanceSupplier<Memories>() {
                        @Override
                        public Memories get(RegisteredBean registeredBean) {
                            ConfigurableListableBeanFactory factory =
                                    registeredBean.getBeanFactory();
                            return new Memories(
                                    factory.getBean(TraceStore.class),
                                    factory.getBean(MemoryRepository.class),
                                    factory.getBean(RuntimeClients.class));
                        }
                    });
            // 组件扫描定义不携带构造参数；清空以防旧值与 supplier 并存（Spring 6 中 supplier 优先）。
            definition.getConstructorArgumentValues().clear();
        }
    }
}
