package com.ymhx.dataplatform.kafka;

import com.google.common.collect.Lists;
import com.ymhx.dataplatform.kafka.domain.ADASNewEnum;
import com.ymhx.dataplatform.kafka.untils.DateUtils;
import com.ymhx.dataplatform.kafka.untils.JdbcUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.springframework.stereotype.Component;
import scala.Tuple2;

import java.io.IOException;
import java.io.Serializable;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class StaticalVehicheWarnInfo implements Serializable {


    /**
     * 每个车队的风险系数
     */
    public void getWarnCoefficient(Integer integer,JavaSparkContext context) throws IOException, SQLException, ParseException {


        //获取所有车辆的terminal_id
        List<String> list = new JdbcUtils().getterminalID();

            for (String vehicleid : list) {


                //查询配置信息
                List<String> configlist = new JdbcUtils().getconfiglist();
                //查询终端id为terminalId的车辆信息
                List<String> run = new JdbcUtils().getTerminalData(vehicleid);
                //倒序
                String reverseId = StringUtils.reverse((run.get(6)));

                //hbase配置
                Configuration hconf = HBaseConfiguration.create();
                hconf.set("hbase.zookeeper.quorum", "192.168.10.117:2181");
                hconf.set("hbase.zookeeper.property.clientPort", "2181");
                hconf.set(TableInputFormat.INPUT_TABLE, "vehicle_alarm_adas");


                Scan scan = new Scan();
                scan.setStartRow(String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("startTime")).getBytes());
                scan.setStopRow(String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("endTime")).getBytes());
                hconf.set(TableInputFormat.SCAN, TableMapReduceUtil.convertScanToString(scan));
                hconf.set(TableInputFormat.SCAN_ROW_START, String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("startTime")));
                hconf.set(TableInputFormat.SCAN_ROW_STOP, String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("endTime")));


                //设置标识符
                //获取符合查询的hbase相应信息
                JavaPairRDD<ImmutableBytesWritable, Result> javaPairRDD = context.newAPIHadoopRDD(hconf, TableInputFormat
                        .class, ImmutableBytesWritable.class, Result.class);
                long count = javaPairRDD.count();
                JavaPairRDD<String, Double> stringDoubleJavaPairRDD = javaPairRDD.mapToPair(new PairFunction<Tuple2<ImmutableBytesWritable, Result>, String, Double>() {
                    @Override
                    public Tuple2<String, Double> call(Tuple2<ImmutableBytesWritable, Result> immutableBytesWritableResultTuple2) throws Exception {
                        Result result = immutableBytesWritableResultTuple2._2();
                        //获取每个车辆的报警类型
                        Integer alarmType = Integer.valueOf(Bytes.toString(result.getValue("alarm".getBytes(), "alarmType".getBytes())));
                        //获取每个车辆的终端ID
                        String vehicleId = Bytes.toString(result.getValue("alarm".getBytes(), "vehicleId".getBytes()));
                        //获取每个车的速度进行判断
                        Double speed = Double.valueOf(Bytes.toString(result.getValue("alarm".getBytes(), "speed".getBytes())));
                        Double marking = 0.00;

                        if (alarmType == ADASNewEnum.FCWANDUFCW.getVaule()) {  //.....前向碰撞
                            //根据速度判断是否为前碰撞还是低速前碰撞 计算相应系数
                            if (speed >= Double.parseDouble(configlist.get(15))) {   //.......前碰撞
                                marking += 1 * Double.parseDouble(configlist.get(3));
                                alarmType = ADASNewEnum.FCW.getVaule();
                            } else if (speed < Double.parseDouble(configlist.get(15)) && speed >= Double.parseDouble(configlist.get(16))) {
                                marking += 1 * Double.parseDouble(configlist.get(4));
                                alarmType = ADASNewEnum.FCW.getVaule();
                            } else if (speed >= Double.parseDouble(configlist.get(17))) {  //......低速碰撞
                                marking += 1 * Double.parseDouble(configlist.get(6));
                                alarmType = ADASNewEnum.UFCW.getVaule();
                            }
                        } else if (alarmType == ADASNewEnum.LDWADNLDWR.getVaule()) { //......车道偏移
                            if (speed >= Double.parseDouble(configlist.get(18))) {
                                marking += 1 * Double.parseDouble(configlist.get(7));
                                alarmType = ADASNewEnum.LDWR.getVaule();
                            } else {
                                marking += 1 * Double.parseDouble(configlist.get(8));
                                alarmType = ADASNewEnum.LDW.getVaule();
                            }
                        } else if (alarmType == ADASNewEnum.PCW.getVaule()) {  //.....行人碰撞
                            marking += 1 * Double.parseDouble(configlist.get(10));
                        } else if (alarmType == ADASNewEnum.HMW.getVaule()) {  //.....车距检测
                            if (speed >= Double.parseDouble(configlist.get(20))) {
                                marking += 1 * Double.parseDouble(configlist.get(11));
                            } else {
                                marking += 1 * Double.parseDouble(configlist.get(12));
                            }
                        } else if (alarmType == ADASNewEnum.TSR.getVaule()) {  //.....超速
                            if (speed >= Double.parseDouble(configlist.get(19))) {
                                marking += 1 * Double.parseDouble(configlist.get(14));
                            } else {
                                marking = marking;
                            }
                        }

                        return new Tuple2<String, Double>(vehicleId + "_" + alarmType, marking);
                    }
                }).reduceByKey(new Function2<Double, Double, Double>() {
                    @Override
                    public Double call(Double aDouble, Double aDouble2) throws Exception {
                        return aDouble + aDouble2;
                    }
                });

                JavaPairRDD<String, String> pairRDD = stringDoubleJavaPairRDD.mapToPair(new PairFunction<Tuple2<String, Double>, String, String>() {
                    @Override
                    public Tuple2<String, String> call(Tuple2<String, Double> stringDoubleTuple2) throws Exception {
                        List<String> asList = Arrays.asList(stringDoubleTuple2._1.split("_"));
                        return new Tuple2<>(asList.get(0), asList.get(1) + "_" + stringDoubleTuple2._2);
                    }
                }).reduceByKey(new Function2<String, String, String>() {
                    @Override
                    public String call(String s, String s2) throws Exception {
                        Double making = 0.00;
                        if (s2.contains("_")) {
                            List<String> values = Arrays.asList(s2.split("_"));
                            int alarmtype = Integer.parseInt(values.get(0));
                            //对前碰撞 车道偏移 车距检测省基数
                            if (alarmtype == ADASNewEnum.FCW.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(5));
                            } else if (alarmtype == ADASNewEnum.LDW.getVaule() || alarmtype == ADASNewEnum.LDWR.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(9));
                            } else if (alarmtype == ADASNewEnum.HMW.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(14));
                            } else {
                                making += Double.parseDouble(values.get(1));
                            }
                        } else {
                            making += Double.parseDouble(s2);
                        }

                        if (s.contains("_")) {
                            List<String> values1 = Arrays.asList(s.split("_"));
                            int alarmtype1 = Integer.parseInt(values1.get(0));
                            if (alarmtype1 == ADASNewEnum.FCW.getVaule()) {
                                making += Double.parseDouble(values1.get(1)) * Double.valueOf(configlist.get(5));
                            } else if (alarmtype1 == ADASNewEnum.LDW.getVaule() || alarmtype1 == ADASNewEnum.LDWR.getVaule()) {
                                making += Double.parseDouble(values1.get(1)) * Double.valueOf(configlist.get(9));
                            } else if (alarmtype1 == ADASNewEnum.HMW.getVaule()) {
                                making += Double.parseDouble(values1.get(1)) * Double.valueOf(configlist.get(14));
                            } else {
                                making += Double.parseDouble(values1.get(1));
                            }
                        } else {
                            making += Double.parseDouble(s);
                        }
                        return String.valueOf(making);
                    }
                });

                pairRDD.foreach(new VoidFunction<Tuple2<String, String>>() {
                    @Override
                    public void call(Tuple2<String, String> stringStringTuple2) throws Exception {
                        //对只有一种类型的进行判断
                        Double making = 0.00;
                        if (stringStringTuple2._2.contains("_")) {
                            List<String> values = Arrays.asList(stringStringTuple2._2.split("_"));
                            int alarmtype = Integer.parseInt(values.get(0));
                            //对前碰撞 车道偏移 车距检测省基数
                            if (alarmtype == ADASNewEnum.FCW.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(5));
                            } else if (alarmtype == ADASNewEnum.LDW.getVaule() || alarmtype == ADASNewEnum.LDWR.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(9));
                            } else if (alarmtype == ADASNewEnum.HMW.getVaule()) {
                                making += Double.parseDouble(values.get(1)) * Double.valueOf(configlist.get(14));
                            } else {
                                making += Double.parseDouble(values.get(1));
                            }
                        } else {
                            //向mysql插入统计数据
                            making = Double.parseDouble(stringStringTuple2._2);
                        }

                        int vehicleId = Integer.parseInt(vehicleid);
                        DateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        //查询是否有该数据
                        String querysql = "SELECT * from tb_vehicle_report WHERE vehicle_id=? AND record_date>=? AND record_date<? ";
                        querysql = String.format(querysql, vehicleId, DateUtils.getBeforeOneDay(integer).get("startTime"), DateUtils.getBeforeOneDay(integer).get("endTime"));
                        List<String> query = new JdbcUtils().query(querysql, vehicleId, DateUtils.getcurrentTime().get("startTime"), DateUtils.getcurrentTime().get("endTime"));
                        if (query.size() == 0) {
                            //不存在进行新增操作
                            String sql = "insert into tb_vehicle_report  (terminal_id,vehicle_id,number_plate,superior_id,warn_risk,create_time,record_date) values (?,?,?,?,?,?,?)";
                            DecimalFormat df = new DecimalFormat("0.00");
                            df.setRoundingMode(RoundingMode.HALF_UP);
                            String format = df.format(making);
                            new JdbcUtils().save(sql, Integer.parseInt(run.get(6)), vehicleId, run.get(1), Integer.parseInt(run.get(11)),
                                    format, dateFmt.format(new Date()), dateFmt.format(DateUtils.getBeforeOneDay(integer).get("startTime")));
                        } else {
                            //以前的risk
                            double oldmaking = Double.parseDouble(query.get(1));
                            String sql = "update tb_vehicle_report set  warn_risk=?,create_time=?  where id=? ";
                            DecimalFormat df = new DecimalFormat("0.00");
                            df.setRoundingMode(RoundingMode.HALF_UP);
                            making = oldmaking + making;
                            String format = df.format(making);
                            new JdbcUtils().update(sql, Integer.parseInt(query.get(0)), format, dateFmt.format(new Date()));
                        }
                    }
                });
            }

    }

    public void getAlarmTypeNum(Integer integer ,JavaSparkContext context) throws ParseException, IOException, SQLException {

        //获取所有车辆的terminal_id
        List<String> list = new JdbcUtils().getterminalID();

            for (String vehicleid : list) {


                //查询配置信息
                List<String> configlist = new JdbcUtils().getconfiglist();
                //查询终端id为terminalId的车辆信息
                List<String> run = new JdbcUtils().getTerminalData(vehicleid);
                //倒序
                String reverseId = StringUtils.reverse((run.get(6)));

                //hbase配置
                Configuration hconf = HBaseConfiguration.create();
                hconf.set("hbase.zookeeper.quorum", "192.168.0.95:2181,192.168.0.46:2181,192.168.0.202:2181");
                hconf.set("hbase.zookeeper.property.clientPort", "2181");
                hconf.set(TableInputFormat.INPUT_TABLE, "vehicle_alarm_adas");


                Scan scan = new Scan();
                scan.setStartRow(String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("startTime")).getBytes());
                scan.setStopRow(String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("endTime")).getBytes());
                hconf.set(TableInputFormat.SCAN, TableMapReduceUtil.convertScanToString(scan));
                hconf.set(TableInputFormat.SCAN_ROW_START, String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("startTime")));
                hconf.set(TableInputFormat.SCAN_ROW_STOP, String.format("%s%s", reverseId, DateUtils.getBeforeOneDay(integer).get("endTime")));


                //设置标识符
                //获取符合查询的hbase相应信息
                JavaPairRDD<ImmutableBytesWritable, Result> javaPairRDD = context.newAPIHadoopRDD(hconf, TableInputFormat
                        .class, ImmutableBytesWritable.class, Result.class);
                long count = javaPairRDD.count();
                //合并数据
                javaPairRDD.mapToPair(new PairFunction<Tuple2<ImmutableBytesWritable, Result>, String, Integer>() {
                    @Override
                    public Tuple2<String, Integer> call(Tuple2<ImmutableBytesWritable, Result> immutableBytesWritableResultTuple2) throws Exception {
                        Result result = immutableBytesWritableResultTuple2._2();
                        //获取每个车辆的报警类型
                        Integer alarmType = Integer.valueOf(Bytes.toString(result.getValue("alarm".getBytes(), "alarmType".getBytes())));
                        //获取每个车辆的终端ID
                        String vehicleId = Bytes.toString(result.getValue("alarm".getBytes(), "vehicleId".getBytes()));
                        //获取每个车的速度进行判断
                        Double speed = Double.valueOf(Bytes.toString(result.getValue("alarm".getBytes(), "speed".getBytes())));


                        if (alarmType == ADASNewEnum.FCWANDUFCW.getVaule()) {  //.....前向碰撞
                            //根据速度判断是否为前碰撞还是低速前碰撞 计算相应系数
                            if (speed >= Double.parseDouble(configlist.get(17))) {
                                alarmType = ADASNewEnum.UFCW.getVaule();         //...... 前碰撞
                            } else {
                                alarmType = ADASNewEnum.FCW.getVaule();          //......低速碰撞
                            }
                        } else if (alarmType == ADASNewEnum.LDWADNLDWR.getVaule()) { //......车道偏移
                            if (speed >= Double.parseDouble(configlist.get(18))) {
                                alarmType = ADASNewEnum.LDWR.getVaule();
                            } else {
                                alarmType = ADASNewEnum.LDW.getVaule();
                            }
                        }
                        return new Tuple2<>(vehicleId + "_" + alarmType, 1);
                    }
                }).reduceByKey(new Function2<Integer, Integer, Integer>() {
                    @Override
                    public Integer call(Integer integer, Integer integer2) throws Exception {

                        return integer + integer2;
                    }
                }).mapToPair(new PairFunction<Tuple2<String, Integer>, String, String>() {
                    @Override
                    public Tuple2<String, String> call(Tuple2<String, Integer> stringIntegerTuple2) throws Exception {
                        List<String> newlist = Arrays.asList(stringIntegerTuple2._1.split("_"));
                        return new Tuple2<>(newlist.get(0), newlist.get(1) + "_" + stringIntegerTuple2._2);
                    }
                }).foreach(new VoidFunction<Tuple2<String, String>>() {
                    @Override
                    public void call(Tuple2<String, String> stringTuple2) throws Exception {
                        System.out.println(stringTuple2._2);
                        System.out.println(stringTuple2._1);
                        DateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        List<String> newlist = Arrays.asList(stringTuple2._2.split("_"));
                        int type = Integer.parseInt(newlist.get(0));
                        int counts = Integer.parseInt(newlist.get(1));

                        //插入报警信息
                        String sql = "update tb_vehicle_report set %s=?  where vehicle_id=? and record_date>=? and record_date<? ";
                        String name = "";
                        if (ADASNewEnum.FCW.getVaule() == type) {  //前碰撞
                            name = "fwc";
                        } else if (ADASNewEnum.UFCW.getVaule() == type) {//低速前碰撞
                            name = "ufcw";
                        } else if (ADASNewEnum.LDW.getVaule() == type) { //车道左偏移
                            name = "ldw";
                        } else if (ADASNewEnum.LDWR.getVaule() == type) { //车道右偏移
                            name = "rdw";
                        } else if (ADASNewEnum.HMW.getVaule() == type) {  //车距检测
                            name = "hmw";
                        } else if (ADASNewEnum.PCW.getVaule() == type) { //行人碰撞
                            name = "pcw";
                        } else if (ADASNewEnum.ACC.getVaule() == type) { //// 驾驶辅助功能失效
                            name = "acc";
                        } else if (ADASNewEnum.TSR.getVaule() == type) { //限速提示
                            name = "tsr";
                        } else if (ADASNewEnum.ODA.getVaule() == type) {
                            name = "oda";
                        } else if (ADASNewEnum.FFW.getVaule() == type) {
                            name = "ffw";
                        } else if (ADASNewEnum.Road_Risking.getVaule() == type) {
                            name = "road_risking";
                        } else if (ADASNewEnum.Active_Capture.getVaule() == type) {
                            name = "active_capture";
                        }
                        if (StringUtils.isNotBlank(name)) {
                            sql = String.format(sql, name);
                            new JdbcUtils().save(sql, String.valueOf(counts), vehicleid, dateFmt.format(DateUtils.getBeforeOneDay(integer).get("startTime")), dateFmt.format(DateUtils.getBeforeOneDay(integer).get("endTime")));
                        }
                    }

                });


            }

    }

    /**
     * 计算里程
     */
    public void getMileageCount(Integer integer,JavaSparkContext context) throws SQLException, ParseException, IOException {


        //获取所有车辆的terminal_id
        List<String> list = new JdbcUtils().getterminalID();

            for (String vehicleid : list) {
                //倒序

                String reverseId = StringUtils.reverse((vehicleid));
                reverseId = String.format("%-14s", reverseId).replace(' ', '0');
                //hbase配置
                Configuration hconf = HBaseConfiguration.create();
                hconf.set("hbase.zookeeper.quorum", "192.168.0.95:2181,192.168.0.46:2181,192.168.0.202:2181");
                hconf.set("hbase.zookeeper.property.clientPort", "2181");
                hconf.set(TableInputFormat.INPUT_TABLE, "tb_vehicle_gps");

                Scan scan = new Scan();
                Long startTime = DateUtils.getBeforeOneDay(integer).get("startTime");
                Long endTime = DateUtils.getBeforeOneDay(integer).get("endTime");
                scan.setStartRow(String.format("%s%s", reverseId, startTime).getBytes());
                scan.setStopRow(String.format("%s%s", reverseId, endTime).getBytes());
                hconf.set(TableInputFormat.SCAN, TableMapReduceUtil.convertScanToString(scan));
                hconf.set(TableInputFormat.SCAN_ROW_START, String.format("%s%s", reverseId, startTime));
                hconf.set(TableInputFormat.SCAN_ROW_STOP, String.format("%s%s", reverseId, endTime));

                //查询终端id为terminalId的车辆信息
                List<String> run = new JdbcUtils().getTerminalData(vehicleid);
                //设置标识符
                //获取符合查询的hbase相应信息
                JavaPairRDD<ImmutableBytesWritable, Result> javaPairRDD = context.newAPIHadoopRDD(hconf, TableInputFormat.class, ImmutableBytesWritable.class, Result.class);
                long count = javaPairRDD.count();
                //统计公里数
                JavaPairRDD<String, Double> mileagerdd = javaPairRDD.mapToPair(new PairFunction<Tuple2<ImmutableBytesWritable, Result>, Integer, String>() {
                    @Override
                    public Tuple2<Integer, String> call(Tuple2<ImmutableBytesWritable, Result> immutableBytesWritableResultTuple2) throws Exception {
                        Result result = immutableBytesWritableResultTuple2._2();
                        DateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        //获取每个车辆的终端ID
                        int terminalId = Bytes.toInt(result.getValue("gps".getBytes(), "terminalId".getBytes()));

                        //获取车辆的公里数
                        String mileage = Bytes.toString(result.getValue("gps".getBytes(), "mileage".getBytes()));
                        //获取时间
                        String warningTime = Bytes.toString(result.getValue("gps".getBytes(), "gpsTime".getBytes()));
                        Date date = new Date(warningTime);
                        warningTime = dateFmt.format(date);
                        return new Tuple2<>(Integer.parseInt(vehicleid), terminalId + "_" + warningTime + "_" + mileage);
                    }
                }).groupByKey().sortByKey(false).mapToPair(new PairFunction<Tuple2<Integer, Iterable<String>>, String, Double>() {
                    @Override
                    public Tuple2<String, Double> call(Tuple2<Integer, Iterable<String>> integerIterableTuple2) throws Exception {
                        Iterable<String> strings = integerIterableTuple2._2();
                        ArrayList<String> newArrayList = Lists.newArrayList(strings);
                        Collections.sort(newArrayList, new Comparator<String>() {
                            @Override
                            public int compare(String o1, String o2) {
                                List<String> newlist = Arrays.asList(o1.split("_"));
                                List<String> oldlist = Arrays.asList(o2.split("_"));
                                if (Integer.parseInt(newlist.get(0)) >= Integer.parseInt(oldlist.get(0))) {
                                    return 0;
                                }
                                return 1;
                            }
                        });
                        Collections.sort(newArrayList, new Comparator<String>() {
                            @Override
                            public int compare(String o1, String o2) {
                                List<String> newlist = Arrays.asList(o1.split("_"));
                                List<String> oldlist = Arrays.asList(o2.split("_"));
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                try {
                                    Date newdate = sdf.parse(newlist.get(1));
                                    Date olddate = sdf.parse(oldlist.get(1));
                                    if (newdate.getTime() >= olddate.getTime()) {
                                        return 0;
                                    }
                                } catch (ParseException e) {
                                    e.printStackTrace();
                                }

                                return 1;
                            }
                        });
                        //得到所有的值
                        List<Double> doubles = new ArrayList<>();
                        for (String s : newArrayList) {
                            List<String> list1 = Arrays.asList(s.split("_"));
                            double value = Double.parseDouble(list1.get(2));
                            doubles.add(value);
                        }
                        Double odd = 0.00;
                        for (int i = 0; i < doubles.size(); i++) {
                            if (i > 0) {
                                double value = doubles.get(i) - doubles.get(i - 1);
                                if (value >= 100) {
                                    value = 0.00;
                                }
                                odd += value;
                            }
                        }
                        return new Tuple2<>(integerIterableTuple2._1.toString(), odd);
                    }
                });
                mileagerdd.foreach(new VoidFunction<Tuple2<String, Double>>() {
                    @Override
                    public void call(Tuple2<String, Double> stringDoubleTuple2) throws Exception {
                        DateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        //格式化日期
                        String start = dateFmt.format(startTime);
                        String end = dateFmt.format(endTime);
                        String sql = "update tb_vehicle_report set mileage=?  where vehicle_id=? and record_date>=? and record_date<? ";
                        new JdbcUtils().save(sql, String.format("%.2f", stringDoubleTuple2._2), stringDoubleTuple2._1, start, end);
                    }
                });
            }
    }
}
