/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.engine;

import com.malhartech.api.ActivationListener;
import com.malhartech.api.Operator;
import com.malhartech.api.Operator.OutputPort;
import com.malhartech.api.Sink;
import com.malhartech.engine.Operators.PortMappingDescriptor;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @param <OPERATOR>
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public abstract class Node<OPERATOR extends Operator> implements Runnable
{
  private static final Logger logger = LoggerFactory.getLogger(Node.class);
  /*
   * if the Component is capable of taking only 1 input, call it INPUT.
   * if the Component is capable of providing only 1 output, call it OUTPUT.
   */
  public static final String INPUT = "input";
  public static final String OUTPUT = "output";
  public final String id;
  protected final HashMap<String, CounterSink<Object>> outputs = new HashMap<String, CounterSink<Object>>();
  @SuppressWarnings(value = "VolatileArrayField")
  protected volatile CounterSink<Object>[] sinks = CounterSink.NO_SINKS;
  protected final int spinMillis = 10;
  protected final int bufferCapacity = 1024 * 1024;
  protected boolean alive;
  protected final OPERATOR operator;
  protected final PortMappingDescriptor descriptor;
  protected long currentWindowId;

  public Node(String id, OPERATOR operator)
  {
    this.id = id;
    this.operator = operator;

    descriptor = new PortMappingDescriptor();
    Operators.describe(operator, descriptor);
  }

  public Operator getOperator()
  {
    return operator;
  }

  public void connectOutputPort(String port, final Sink<Object> sink)
  {
    @SuppressWarnings("unchecked")
    OutputPort<Object> outputPort = (OutputPort<Object>)descriptor.outputPorts.get(port);
    if (outputPort != null) {
      if (sink instanceof CounterSink) {
        outputPort.setSink(sink);
        outputs.put(port, (CounterSink<Object>)sink);
      }
      else if (sink == null) {
        outputPort.setSink(null);
        outputs.remove(port);
      }
      else {
        /*
         * if streams implemented CounterSink, this would not get called.
         */
        CounterSink<Object> cs = new CounterSink<Object>()
        {
          int count;

          @Override
          public void process(Object tuple)
          {
            count++;
            sink.process(tuple);
          }

          @Override
          public int getCount()
          {
            return count;
          }

          @Override
          public int resetCount()
          {
            int ret = count;
            count = 0;
            return ret;
          }

        };
        outputPort.setSink(cs);
        outputs.put(port, cs);
      }
    }
  }

  public abstract Sink<Object> connectInputPort(String port, final Sink<? extends Object> sink);

  OperatorContext context;

  @SuppressWarnings("unchecked")
  public void activate(OperatorContext context)
  {
    boolean activationListener = operator instanceof ActivationListener;

    activateSinks();
    alive = true;
    this.context = context;

    if (activationListener) {
      ((ActivationListener)operator).activate(context);
    }

    run();

    if (activationListener) {
      ((ActivationListener)operator).deactivate();
    }

    this.context = null;
    emitEndStream();
    deactivateSinks();
  }

  public void deactivate()
  {
    alive = false;
    //logger.info("deactivated", new Exception());
  }

  @Override
  public boolean equals(Object obj)
  {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final Node<?> other = (Node<?>)obj;
    if ((this.id == null) ? (other.id != null) : !this.id.equals(other.id)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode()
  {
    return id == null ? super.hashCode() : id.hashCode();
  }

  @Override
  public String toString()
  {
    return id;
  }

  protected void emitEndStream()
  {
//    logger.debug("{} sending EndOfStream", this);
    /*
     * since we are going away, we should let all the downstream operators know that.
     */
    EndStreamTuple est = new EndStreamTuple();
    est.windowId = currentWindowId;
    for (final CounterSink<Object> output: outputs.values()) {
      output.process(est);
    }
  }

  public void emitCheckpoint(long windowId)
  {
    CheckpointTuple ct = new CheckpointTuple();
    ct.windowId = currentWindowId;
    for (final CounterSink<Object> output: outputs.values()) {
      output.process(ct);
    }
  }

  protected void handleRequests(long windowId)
  {
    /*
     * we prefer to cater to requests at the end of the window boundary.
     */
    try {
      BlockingQueue<OperatorContext.NodeRequest> requests = context.getRequests();
      int size;
      if ((size = requests.size()) > 0) {
        while (size-- > 0) {
          //logger.debug("endwindow: " + t.getWindowId() + " lastprocessed: " + context.getLastProcessedWindowId());
          requests.remove().execute(operator, context.getId(), windowId);
        }
      }
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }

    OperatorStats stats = new OperatorStats();
    reportStats(stats);

    context.report(stats, windowId);
  }

  protected void reportStats(OperatorStats stats)
  {
    stats.ouputPorts = new ArrayList<OperatorStats.PortStats>();
    for (Entry<String, CounterSink<Object>> e: outputs.entrySet()) {
      stats.ouputPorts.add(new OperatorStats.PortStats(e.getKey(), e.getValue().resetCount()));
    }
  }

  protected void activateSinks()
  {
    int size = outputs.size();
    if (size == 0) {
      sinks = CounterSink.NO_SINKS;
    }
    else {
      @SuppressWarnings("unchecked")
      CounterSink<Object>[] newSinks = (CounterSink<Object>[])Array.newInstance(CounterSink.class, size);
      for (CounterSink<Object> s: outputs.values()) {
        newSinks[--size] = s;
      }

      sinks = newSinks;
    }
  }

  protected void deactivateSinks()
  {
    sinks = CounterSink.NO_SINKS;
  }

  public boolean isAlive()
  {
    return alive;
  }

}
