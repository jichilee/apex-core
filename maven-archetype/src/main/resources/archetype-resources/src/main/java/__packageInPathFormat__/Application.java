#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package ${package};

import org.apache.hadoop.conf.Configuration;

import com.datatorrent.api.StreamingApplication;
import com.datatorrent.api.DAG;
import com.datatorrent.api.DAG.Locality;
import com.datatorrent.lib.io.ConsoleOutputOperator;
import com.datatorrent.lib.testbench.SeedEventGenerator;

public class Application implements StreamingApplication
{

  @Override
  public void populateDAG(DAG dag, Configuration conf)
  {
    // Sample DAG with 2 operators

    SeedEventGenerator seedGen = dag.addOperator("seedGen", SeedEventGenerator.class);
    seedGen.setSeedstart(1);
    seedGen.setSeedend(10);
    seedGen.addKeyData("x", 0, 10);
    seedGen.addKeyData("y", 0, 100);

    ConsoleOutputOperator cons = dag.addOperator("console", new ConsoleOutputOperator());
    cons.setStringFormat("hello: %s");

    dag.addStream("seeddata", seedGen.val_list, cons.input).setLocality(Locality.CONTAINER_LOCAL);
  }
}